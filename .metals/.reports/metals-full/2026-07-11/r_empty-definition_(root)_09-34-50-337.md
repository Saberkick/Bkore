error id: file://<WORKSPACE>/src/main/scala/mycpu/SramToAxiBridge.scala:
file://<WORKSPACE>/src/main/scala/mycpu/SramToAxiBridge.scala
empty definition using pc, found symbol in pc: 
empty definition using semanticdb
empty definition using fallback
non-local guesses:
	 -chisel3/io/data_sram/wr.
	 -chisel3/io/data_sram/wr#
	 -chisel3/io/data_sram/wr().
	 -chisel3/util/io/data_sram/wr.
	 -chisel3/util/io/data_sram/wr#
	 -chisel3/util/io/data_sram/wr().
	 -io/data_sram/wr.
	 -io/data_sram/wr#
	 -io/data_sram/wr().
	 -scala/Predef.io.data_sram.wr.
	 -scala/Predef.io.data_sram.wr#
	 -scala/Predef.io.data_sram.wr().
offset: 525
uri: file://<WORKSPACE>/src/main/scala/mycpu/SramToAxiBridge.scala
text:
```scala
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
    val data_req_read  = io.data_sram.req && !io.data_sram.wr
    val data_req_write = io.data_sram.req && io.data_sram.@@wr

    // =========================================================================
    // 【终极防御】防“写后读(RAW)”冒险机制
    // 书中铁律：只要有写请求发出，在收到写响应(B通道)前，绝对禁止发起新的读请求！
    // =========================================================================
    val write_pending = RegInit(false.B)
    val w_idle_state = Wire(Bool()) // 提前声明写状态机的空闲标志

    when(w_idle_state && data_req_write) {
        write_pending := true.B     // CPU 发起写，立刻挂起警戒牌
    } .elsewhen(io.axi.bvalid && io.axi.bready) {
        write_pending := false.B    // AXI 返回写成功确认，解除警戒
    }
    
    // 安全读条件：没有挂起的写，且当前周期 CPU 没有刚发出写
    val safe_to_read = !write_pending && !data_req_write

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
            ar_addr_reg := io.data_sram.addr
            ar_size_reg := Cat(0.U(1.W), io.data_sram.size) 
        } .otherwise {
            ar_grant_id := 0.U 
            ar_addr_reg := io.inst_cache.rd_addr // 使用 Cache 锁存的块首地址
            ar_size_reg := 2.U                   // 致命修正：每拍 4 字节，固定为 2.U
        }
    } .elsewhen(ar_state === ar_wait_ready && io.axi.arready) {
        ar_state := ar_idle
    }

    io.axi.arvalid := (ar_state === ar_wait_ready) 
    io.axi.arid    := ar_grant_id
    io.axi.araddr  := ar_addr_reg
    io.axi.arsize  := ar_size_reg
    io.axi.arlen   := Mux(ar_grant_id === 0.U, 3.U, 0.U) // 致命修正：指令端 Miss 触发 4 拍突发传输 (3.U)，数据端维持单拍 (0.U)
    io.axi.arburst := "b01".U  // INCR 模式
    io.axi.arlock  := 0.U      //
    io.axi.arcache := 0.U      //
    io.axi.arprot  := 0.U      //

    // =========================================================================
    // 状态机 2：读响应通道 (R)
    // 任务：指令端直通透传支持 Burst，数据端维持单拍 SRAM 锁存
    // =========================================================================
    val r_data_valid = RegInit(false.B)
    val r_data_data  = RegInit(0.U(32.W))

    io.axi.rready := true.B // CPU 永远准备好接收总线数据

    // 【关键修正】指令端直通：当总线返回数据且 ID 为 0 时，直接把有效信号、结束标志和数据送给 ICache
    // ICache 内部的 Refill 状态机会根据 ret_valid 和 ret_last 自行计数并写入数据 BRAM
    io.inst_cache.ret_valid := io.axi.rvalid && (io.axi.rid === 0.U)
    io.inst_cache.ret_last  := io.axi.rlast
    io.inst_cache.ret_data  := io.axi.rdata
    
    // 数据端（目前还是类 SRAM 接口）维持原有单拍锁存逻辑不变
    when(io.axi.rvalid && io.axi.rready && io.axi.rid === 1.U) {
        r_data_valid := true.B
        r_data_data  := io.axi.rdata
    }

    when(r_data_valid) { r_data_valid := false.B }

    // =========================================================================
    // 状态机 3：写请求 (AW) 与 写数据 (W) 通道
    // 任务：将 data_sram 的写请求分解，AW和W可以同时发，也可以分别完成
    // =========================================================================
    val w_idle :: w_wait_all :: w_wait_aw :: w_wait_w :: Nil = Enum(4)
    val w_state = RegInit(w_idle)
    w_idle_state := (w_state === w_idle)

    val aw_addr_reg = RegInit(0.U(32.W))
    val aw_size_reg = RegInit(0.U(3.W))
    val w_data_reg  = RegInit(0.U(32.W))
    val w_strb_reg  = RegInit(0.U(4.W))

    when(w_state === w_idle) {
        when(data_req_write) {
            w_state := w_wait_all
            aw_addr_reg := io.data_sram.addr
            aw_size_reg := Cat(0.U(1.W), io.data_sram.size)
            w_data_reg  := io.data_sram.wdata
            w_strb_reg  := io.data_sram.wstrb
        }
    } .elsewhen(w_state === w_wait_all) {
        when(io.axi.awready && io.axi.wready) {
            w_state := w_idle      // 两个通道同时接收
        } .elsewhen(io.axi.awready) {
            w_state := w_wait_w    // 地址被收了，数据还没
        } .elsewhen(io.axi.wready) {
            w_state := w_wait_aw   // 数据被收了，地址还没
        }
    } .elsewhen(w_state === w_wait_aw) {
        when(io.axi.awready) { w_state := w_idle }
    } .elsewhen(w_state === w_wait_w) {
        when(io.axi.wready) { w_state := w_idle }
    }

    io.axi.awvalid := (w_state === w_wait_all) || (w_state === w_wait_aw)
    io.axi.awid    := 1.U      // 写通道 ID 固定为 1
    io.axi.awaddr  := aw_addr_reg
    io.axi.awsize  := aw_size_reg
    io.axi.awlen   := 0.U      //
    io.axi.awburst := "b01".U  //
    io.axi.awlock  := 0.U      //
    io.axi.awcache := 0.U      //
    io.axi.awprot  := 0.U      //
    
    io.axi.wvalid  := (w_state === w_wait_all) || (w_state === w_wait_w)
    io.axi.wid     := 1.U      //
    io.axi.wdata   := w_data_reg
    io.axi.wstrb   := w_strb_reg
    io.axi.wlast   := true.B   //

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
    
    // 【关键修正】取指端口路由切换为 ICache AXI 侧接口
    io.inst_cache.rd_rdy := (ar_state === ar_idle) && inst_req_read && !data_req_read && safe_to_read
    io.inst_cache.wr_rdy := true.B // ICache 恒不需要写回内存，写就绪直接给 true.B 防止状态机卡死

    // 访存端口路由 (MEM) —— 保持你原来的设计不动
    val data_ar_ok = (ar_state === ar_idle) && data_req_read && safe_to_read
    val data_aw_ok = (w_state === w_idle) && data_req_write
    
    io.data_sram.addr_ok := data_ar_ok || data_aw_ok
    io.data_sram.data_ok := r_data_valid || b_valid_buf
    io.data_sram.rdata   := r_data_data
}
```


#### Short summary: 

empty definition using pc, found symbol in pc: 