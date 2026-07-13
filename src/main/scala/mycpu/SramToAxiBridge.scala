package mycpu

import chisel3._
import chisel3.util._

class SramToAxiBridge extends Module {
    val io = IO(new Bundle {
        // 两个类 SRAM 从设备接口（左手牵 CPU）
        val inst_cache = Flipped(new CacheToAxiIO())
        val data_cache = Flipped(new CacheToAxiIO())
        
        // 一个 AXI 主设备接口（右手牵总线）
        val axi = new AxiIO()
    })

    // 提取有效的读写请求
    val inst_req_read  = io.inst_cache.rd_req
    val data_req_read  = io.data_cache.rd_req
    val data_req_write = io.data_cache.wr_req

    val w_idle :: w_wait_all :: w_wait_aw :: w_wait_w :: Nil = Enum(4)
    val w_state = RegInit(w_idle)

    // =========================================================================
    // 【终极防御】防“写后读(RAW)”冒险机制
    // 书中铁律：只要有写请求发出，在收到写响应(B通道)前，绝对禁止发起新的读请求！
    // =========================================================================
    val write_pending = RegInit(false.B)

    // 只有非缓存写（单拍 4 字节，wr_type === 2.U）才触发严格的 bvalid 阻塞
    val is_uncached_write = data_req_write && (io.data_cache.wr_type === 2.U)

    // 真正受理非缓存写请求的那一拍，挂起警戒牌
    when(w_state === w_idle && is_uncached_write && !write_pending) {
        write_pending := true.B     
    } .elsewhen(io.axi.bvalid && io.axi.bready) {
        write_pending := false.B    // AXI 返回写成功确认，解除警戒
    }
    
    // 安全条件：警戒牌高悬期间，不接任何新客（读写全阻塞）
    val safe_to_read  = !write_pending
    val safe_to_write = !write_pending

    // =========================================================================
    // 状态机 1：读请求通道 (AR)
    // 任务：将 inst_sram 和 data_sram 的读请求仲裁后发送到 AR 通道
    // =========================================================================
    val ar_idle :: ar_wait_ready :: Nil = Enum(2)
    val ar_state = RegInit(ar_idle)

    val ar_grant_id = RegInit(0.U(4.W))
    val ar_addr_reg = RegInit(0.U(32.W))
    val ar_size_reg = RegInit(0.U(3.W))

    // 读请求开火条件：AR通道空闲 + 有读请求 + 没触发 RAW 冒险
    val ar_fire = (ar_state === ar_idle) && (data_req_read || inst_req_read) && safe_to_read

    when(ar_fire) {
        ar_state := ar_wait_ready
        // 仲裁：数据访存优先级绝对高于取指
        when(data_req_read) {
            ar_grant_id := 1.U // 数据读 ARID 固定为 1
            ar_addr_reg := io.data_cache.rd_addr
            ar_size_reg := io.data_cache.rd_type
        }
        .otherwise {
            ar_grant_id := 0.U 
            ar_addr_reg := io.inst_cache.rd_addr
            ar_size_reg := io.inst_cache.rd_type // 正确做法：ICache 也会发 4.U(Burst) 或 2.U(Uncached)
        }
    } .elsewhen(ar_state === ar_wait_ready && io.axi.arready) {
        ar_state := ar_idle
    }

    io.axi.arvalid := (ar_state === ar_wait_ready) 
    io.axi.arid    := ar_grant_id
    io.axi.araddr  := ar_addr_reg
    // 在 AR 状态机赋值处：
    // 直接用锁存好的大小判断。4.U 代表 16 字节缓存行，需要 4 拍 (arlen=3)。否则单拍 (arlen=0)
    io.axi.arlen  := Mux(ar_size_reg === 4.U, 3.U, 0.U)
    io.axi.arsize := 2.U // 无论突发还是单拍，每一拍的数据量永远是 4 字节 (3b'010)
    io.axi.arburst := "b01".U  // INCR 模式
    io.axi.arlock  := 0.U      //
    io.axi.arcache := 0.U      //
    io.axi.arprot  := 0.U      //

    // =========================================================================
    // 状态机 2：读响应通道 (R)
    // 任务：指令端直通透传支持 Burst，数据端维持单拍 SRAM 锁存
    // =========================================================================
    io.axi.rready := true.B // CPU 永远准备好接收总线数据

    io.inst_cache.ret_valid := io.axi.rvalid && (io.axi.rid === 0.U)
    io.inst_cache.ret_last  := io.axi.rlast
    io.inst_cache.ret_data  := io.axi.rdata

    // 【关键修正】指令端直通：当总线返回数据且 ID 为 0 时，直接把有效信号、结束标志和数据送给 ICache
    // ICache 内部的 Refill 状态机会根据 ret_valid 和 ret_last 自行计数并写入数据 BRAM
    io.data_cache.ret_valid := io.axi.rvalid && (io.axi.rid === 1.U)
    io.data_cache.ret_last  := io.axi.rlast
    io.data_cache.ret_data  := io.axi.rdata

    // =========================================================================
    // 状态机 3：写请求 (AW) 与 写数据 (W) 通道
    // 任务：将 data_sram 的写请求分解，AW和W可以同时发，也可以分别完成
    // =========================================================================
    

    val aw_addr_reg = RegInit(0.U(32.W))
    val aw_size_reg = RegInit(0.U(3.W))
    val w_beat_cnt = RegInit(0.U(2.W))
    val w_data_reg = RegInit(0.U(128.W))
    val w_strb_reg  = RegInit(0.U(4.W))

    val aw_fire  = io.axi.awready && io.axi.awvalid
    val w_fire   = io.axi.wready && io.axi.wvalid
    val w_finish = w_fire && io.axi.wlast

    when(w_state === w_idle) {
        // 【修改这里】：加上 safe_to_write
        when(data_req_write && safe_to_write) { 
            w_state := w_wait_all
            aw_addr_reg := io.data_cache.wr_addr
            aw_size_reg := io.data_cache.wr_type
            w_data_reg  := io.data_cache.wr_data
            w_strb_reg  := io.data_cache.wr_wstrb
            w_beat_cnt  := 0.U
        }
    } .elsewhen(w_state === w_wait_all) {
        // 必须等地址收走，且数据全传完（w_finish），才能回 idle
        when(aw_fire && w_finish) { w_state := w_idle }
        .elsewhen(aw_fire)        { w_state := w_wait_w }
        .elsewhen(w_finish)       { w_state := w_wait_aw }
    } .elsewhen(w_state === w_wait_aw) {
        when(aw_fire) { w_state := w_idle }
    } .elsewhen(w_state === w_wait_w) {
        when(w_finish) { w_state := w_idle }
    }

    // 拍数计数器递增逻辑（不在最后一拍时加 1）
    when(w_fire && !io.axi.wlast) {
        w_beat_cnt := w_beat_cnt + 1.U
    }

    val is_burst_write = (aw_size_reg === 4.U)

    io.axi.awvalid := (w_state === w_wait_all) || (w_state === w_wait_aw)
    io.axi.awid    := 1.U      // 写通道 ID 固定为 1
    io.axi.awaddr  := aw_addr_reg
    io.axi.awsize  := 2.U
    io.axi.awlen   := Mux(is_burst_write, 3.U, 0.U)
    io.axi.awburst := "b01".U  //
    io.axi.awlock  := 0.U      //
    io.axi.awcache := 0.U      //
    io.axi.awprot  := 0.U      //
    
    io.axi.wvalid  := (w_state === w_wait_all) || (w_state === w_wait_w)
    io.axi.wid     := 1.U      //
    io.axi.wdata := MuxLookup(w_beat_cnt, w_data_reg(31, 0))(Seq(
                    0.U -> w_data_reg(31, 0),
                    1.U -> w_data_reg(63, 32),
                    2.U -> w_data_reg(95, 64),
                    3.U -> w_data_reg(127, 96)
                ))
    io.axi.wstrb := Mux(is_burst_write, "hf".U, w_strb_reg)
    io.axi.wlast := Mux(is_burst_write, w_beat_cnt === 3.U, true.B)

    // =========================================================================
    // 状态机 4：写响应通道 (B)
    // =========================================================================
    val b_valid_buf = RegInit(false.B)
    io.axi.bready := true.B
    
    when(io.axi.bvalid && io.axi.bready) {
        b_valid_buf := true.B
    }
    when(b_valid_buf) {
        b_valid_buf := false.B
    }

    // =========================================================================
    // 最终：把分离的 AXI 信号，“翻译”回控制信号，反馈给 CPU/Cache
    // =========================================================================
    
    // 1. 指令端 (ICache)
    io.inst_cache.rd_rdy := (ar_state === ar_idle) && inst_req_read && !data_req_read && safe_to_read
    io.inst_cache.wr_rdy := true.B // ICache 恒不需要写回内存

    // 2. 数据端 (DCache)
    io.data_cache.rd_rdy := (ar_state === ar_idle) && data_req_read && safe_to_read
    
    // 书本 P248 明确规定：wr_rdy 表示桥内部缓冲为空，绝不能依赖 AXI 握手！
    // 修改为：
    // 书本 P248 规定：wr_rdy 表示桥内部缓冲为空。但为了强序非缓存保护，
    // 如果上一个写操作还没等回 bvalid (警戒牌高悬)，桥必须装作“我很忙”，拒绝接客！
    io.data_cache.wr_rdy := (w_state === w_idle) && !write_pending

}