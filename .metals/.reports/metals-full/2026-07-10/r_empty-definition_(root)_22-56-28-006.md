error id: file://<WORKSPACE>/src/main/scala/mycpu/Cache.scala:mycpu/Cache#lookup_write_hit.
file://<WORKSPACE>/src/main/scala/mycpu/Cache.scala
empty definition using pc, found symbol in pc: 
empty definition using semanticdb
empty definition using fallback
non-local guesses:
	 -chisel3/lookup_write_hit.
	 -chisel3/lookup_write_hit#
	 -chisel3/lookup_write_hit().
	 -chisel3/util/lookup_write_hit.
	 -chisel3/util/lookup_write_hit#
	 -chisel3/util/lookup_write_hit().
	 -lookup_write_hit.
	 -lookup_write_hit#
	 -lookup_write_hit().
	 -scala/Predef.lookup_write_hit.
	 -scala/Predef.lookup_write_hit#
	 -scala/Predef.lookup_write_hit().
offset: 8821
uri: file://<WORKSPACE>/src/main/scala/mycpu/Cache.scala
text:
```scala
package mycpu

import chisel3._
import chisel3.util._

// ==============================================================
// 1. 接口定义 (Bundles)
// ==============================================================

// CPU 与 Cache 的交互接口 (参考书本表 10.2)
class CacheToCpuIO extends Bundle {
    val valid  = Input(Bool())
    val op     = Input(Bool()) // 0: Read, 1: Write
    val index  = Input(UInt(8.W))
    val tag    = Input(UInt(20.W)) // 来自 TLB 的物理 Tag，与 index 同拍到达
    val offset = Input(UInt(4.W))
    val wstrb  = Input(UInt(4.W))
    val wdata  = Input(UInt(32.W))

    val addr_ok = Output(Bool())
    val data_ok = Output(Bool())
    val rdata   = Output(UInt(32.W))
}

// Cache 与 AXI 转接桥的交互接口 (参考书本表 10.3)
class CacheToAxiIO extends Bundle {
    val rd_req   = Output(Bool())
    val rd_type  = Output(UInt(3.W))  // 3 代表 16 字节 Cache 行
    val rd_addr  = Output(UInt(32.W))
    val rd_rdy   = Input(Bool())
    val ret_valid= Input(Bool())
    val ret_last = Input(Bool())
    val ret_data = Input(UInt(32.W))  // AXI 每次只传回来 32 位

    val wr_req   = Output(Bool())
    val wr_type  = Output(UInt(3.W))  // 3 代表 16 字节 Cache 行
    val wr_addr  = Output(UInt(32.W))
    val wr_wstrb = Output(UInt(4.W))
    val wr_data  = Output(UInt(128.W))// 写回时，一口气吐出 128 位
    val wr_rdy   = Input(Bool())
}

// ==============================================================
// 2. Cache 顶层模块骨架
// ==============================================================
class Cache extends Module {
    val io = IO(new Bundle {
        val cpu = new CacheToCpuIO()
        val axi = new CacheToAxiIO()
    })

    // -----------------------------------------------------------
    // 2.1 状态机定义
    // -----------------------------------------------------------
    // 主状态机 (5个状态)
    val sIdle :: sLookup :: sMiss :: sReplace :: sRefill :: Nil = Enum(5)
    val main_state = RegInit(sIdle)

    // Write Buffer 状态机 (2个状态)
    val wbIdle :: wbWrite :: Nil = Enum(2)
    val wb_state = RegInit(wbIdle)

    // -----------------------------------------------------------
    // 2.2 物理存储阵列 (12张表)
    // -----------------------------------------------------------
    // 1. TagV 表 (2块，单端口同步 RAM，256深度 x 21宽)
    val tagv_way0 = SyncReadMem(256, UInt(21.W))
    val tagv_way1 = SyncReadMem(256, UInt(21.W))

    // 2. Data Bank 表 (8块，单端口同步 RAM，支持字节写使能)
    // 使用 Vec(4, UInt(8.W)) 就可以让 Chisel 推断出带字节掩码的 BRAM！
    val data_way0_bank0 = SyncReadMem(256, Vec(4, UInt(8.W)))
    val data_way0_bank1 = SyncReadMem(256, Vec(4, UInt(8.W)))
    val data_way0_bank2 = SyncReadMem(256, Vec(4, UInt(8.W)))
    val data_way0_bank3 = SyncReadMem(256, Vec(4, UInt(8.W)))
    
    val data_way1_bank0 = SyncReadMem(256, Vec(4, UInt(8.W)))
    val data_way1_bank1 = SyncReadMem(256, Vec(4, UInt(8.W)))
    val data_way1_bank2 = SyncReadMem(256, Vec(4, UInt(8.W)))
    val data_way1_bank3 = SyncReadMem(256, Vec(4, UInt(8.W)))

    // 3. Dirty 表 (2块，用纯寄存器实现，读写互不干涉，且方便全部清零)
    val dirty_way0 = RegInit(VecInit(Seq.fill(256)(false.B)))
    val dirty_way1 = RegInit(VecInit(Seq.fill(256)(false.B)))

    // -----------------------------------------------------------
    // 2.3 核心外围缓冲 (Buffers)
    // -----------------------------------------------------------
    // 1. Request Buffer (保护案发现场)
    val req_op     = RegInit(false.B)
    val req_index  = RegInit(0.U(8.W))
    val req_tag    = RegInit(0.U(20.W))
    val req_offset = RegInit(0.U(4.W))
    val req_wstrb  = RegInit(0.U(4.W))
    val req_wdata  = RegInit(0.U(32.W))

    // 2. Miss Buffer (拆迁办后勤)
    val miss_replace_way = RegInit(false.B) // 0 踢 way0, 1 踢 way1
    val miss_ret_count   = RegInit(0.U(2.W)) // 记录 AXI 返回了几个字 (0~3)

    // 3. Write Buffer (延时写)
    val wb_way   = RegInit(false.B)
    val wb_index = RegInit(0.U(8.W))
    val wb_offset= RegInit(0.U(4.W))
    val wb_wstrb = RegInit(0.U(4.W))
    val wb_wdata = RegInit(0.U(32.W))

    // 4. LFSR 摇号机 (伪随机替换)
    val lfsr = RegInit("hACE1".U(16.W))
    lfsr := Cat(lfsr(14, 0), lfsr(15) ^ lfsr(13) ^ lfsr(12) ^ lfsr(10)) // 简单的伽罗瓦LFSR
    val random_way = lfsr(0)

    // ==============================================================
    // 3. 核心控制逻辑与连线 (Step 2 新增)
    // ==============================================================
    
    // 3.1 SRAM 读地址控制 (地址给进去，下一拍出数据)
    // 如果是闲着或者刚来新指令，查 CPU 给的 index；如果是处理 Miss，查锁在 Request Buffer 里的 index
    val current_index = Mux(main_state === sIdle || main_state === sLookup, io.cpu.index, req_index)

    // 发起读取 (TagV 表)
    val tagv_rdata_way0 = tagv_way0.read(current_index)
    val tagv_rdata_way1 = tagv_way1.read(current_index)

    // 3.2 Tag 比较裁判 (在 Cycle 2 也就是 LOOKUP 状态拿到读出结果)
    val way0_v   = tagv_rdata_way0(0)
    val way0_tag = tagv_rdata_way0(20, 1)
    val way0_hit = way0_v && (way0_tag === req_tag) // 注意：是跟 req_tag (案发现场) 比！

    val way1_v   = tagv_rdata_way1(0)
    val way1_tag = tagv_rdata_way1(20, 1)
    val way1_hit = way1_v && (way1_tag === req_tag)

    val cache_hit = way0_hit || way1_hit

    // 3.3 Request Buffer 快门逻辑 (记录案发现场)
    
    // 3.3.1 真实的写后读冲突检测 (Hazard)
    val hazard_lookup_write = (main_state === sLookup) && (req_op === true.B)
    // 情况1：主状态机在 LOOKUP 阶段，且当前指令是命中写 (Store Hit)
    val real_write_hit = (main_state === sLookup) && cache_hit && (req_op === true.B)
    
    // 情况2：Write Buffer 状态机在 WRITE 阶段，正在努力写 SRAM
    val wb_is_writing = (wb_state === wbWrite)
    
    // 新来的请求是 Load 读操作
    val new_is_read = io.cpu.valid && (io.cpu.op === false.B)

    // 新来的请求不管读写，只要地址冲突了就得拦下来！
    val new_req_valid = io.cpu.valid
    // 冲突条件：新读请求的 Index 和 字内偏移(Offset的高2位) 与正在写的地址完全重叠！
    // (因为同一个字正在被改写，不能马上读，必须阻塞 Stall)
    val hazard_with_lookup = hazard_lookup_write && (io.cpu.index === req_index) && (io.cpu.offset(3,2) === req_offset(3,2))
    val hazard_with_wb     = wb_is_writing && (io.cpu.index === wb_index) && (io.cpu.offset(3,2) === wb_offset(3,2))
    
    val hit_write_hazard = new_req_valid && (hazard_with_lookup || hazard_with_wb)



    // 什么时候吸纳新指令？ (也就是 addr_ok 什么时候拉高)
    val can_accept_new = (main_state === sIdle) || 
                         (main_state === sLookup && cache_hit && (!io.cpu.op || (io.cpu.op && !hit_write_hazard)))

    io.cpu.addr_ok := can_accept_new

    // 按下快门！只要 addr_ok 拉高且有 valid 请求，立刻把 CPU 的输入锁进 Request Buffer
    when(io.cpu.valid && io.cpu.addr_ok) {
        req_op     := io.cpu.op
        req_index  := io.cpu.index
        req_tag    := io.cpu.tag
        req_offset := io.cpu.offset
        req_wstrb  := io.cpu.wstrb
        req_wdata  := io.cpu.wdata
    }
    // 3.4 放行与握手 (data_ok)
    // 满足以下任一条件，告诉 CPU 这条指令干完了：
    // 1. LOOKUP 阶段，并且命中了 (不管是 Load 还是 Store)
    // 2. LOOKUP 阶段，并且这是一个 Store 操作 (因为哪怕 Miss，Store 也可以扔给后面慢慢搞，CPU 先走)
    // 【书本 10.1.3.4 高级优化】：REFILL 阶段，AXI 返回有效数据，且返回的字号正好等于 CPU 请求的字号！
    val refill_bypass_match = (main_state === sRefill) && io.axi.ret_valid && (miss_ret_count === req_offset(3,2))

    io.cpu.data_ok := (main_state === sLookup && cache_hit) || 
                      (main_state === sLookup && req_op === true.B) ||
                      refill_bypass_match // 命中优化条件，提前一拍让 CPU 解除阻塞！

    // ==============================================================
    // 4. 双状态机流转逻辑 (Step 3 新增)
    // ==============================================================

    // 4.1 主状态机 (5个状态)
    switch(main_state) {
        is(sIdle) {
            // 闲置时，有请求且没冲突，直接进入查表
            when(io.cpu.valid && !hit_write_hazard) {
                main_state := sLookup
            }
        }
        is(sLookup) {
            when(cache_hit) {
                // 命中了！如果有新请求且无冲突，流水线狂奔不停；否则回去闲置
                when(io.cpu.valid && !hit_write_hazard) {
                    main_state := sLookup 
                } .otherwise {
                    main_state := sIdle
                }
            } .otherwise {
                // 没命中，漏了！进入漫长的补救流程
                main_state := sMiss       
            }
        }
        is(sMiss) {
            // 等待 AXI 说：“我的写通道准备好了”
            when(io.axi.wr_rdy) {
                main_state := sReplace    
                miss_replace_way := random_way // 摇号机决定踢哪一路！存进 Miss Buffer
            }
        }
        is(sReplace) {
            // 等待 AXI 说：“我的读通道准备好了”
            when(io.axi.rd_rdy) {
                main_state := sRefill     
                miss_ret_count := 0.U     // 计数器清零，准备接收 4 个字的快递
            }
        }
        is(sRefill) {
            // 每次 AXI 吐回一个有效数据，计数器 +1
            when(io.axi.ret_valid) {
                miss_ret_count := miss_ret_count + 1.U
                // 如果这是最后一个数据 (16字节传完了)
                when(io.axi.ret_last) {
                    main_state := sIdle   // 功德圆满，回家接客！
                }
            }
        }
    }

    // 4.2 Write Buffer 状态机 (给命中写擦屁股的后台任务)
    switch(wb_state) {
        is(wbIdle) {
            // 只要主状态机发现命中写，立刻启动后台写任务
            when(real_w@@rite_hitt) { 
                wb_state := wbWrite
                wb_way   := Mux(way1_hit, true.B, false.B) // 记下命中的是哪一路
                wb_index := req_index
                wb_offset:= req_offset
                wb_wstrb := req_wstrb
                wb_wdata := req_wdata
            }
        }
        is(wbWrite) {
            // 如果连续不断地发生命中写，就一直在 WRITE 状态加班
            when(lookup_write_hit) { 
                wb_state := wbWrite
                wb_way   := Mux(way1_hit, true.B, false.B)
                wb_index := req_index
                wb_offset:= req_offset
                wb_wstrb := req_wstrb
                wb_wdata := req_wdata
            } .otherwise {
                wb_state := wbIdle // 活干完了，回去闲置
            }
        }
    }
    // ==============================================================
    // 5. 终极数据连线 (Step 4 新增)
    // ==============================================================

    // 5.1 统一读取 Data Bank 与数据选择
    // 警告：Chisel 中对同一个 SyncReadMem 只能调用一次 .read()，否则会生成多端口 RAM 导致 FPGA 报错！
    val rdata_w0_b0 = data_way0_bank0.read(current_index)
    val rdata_w0_b1 = data_way0_bank1.read(current_index)
    val rdata_w0_b2 = data_way0_bank2.read(current_index)
    val rdata_w0_b3 = data_way0_bank3.read(current_index)

    val rdata_w1_b0 = data_way1_bank0.read(current_index)
    val rdata_w1_b1 = data_way1_bank1.read(current_index)
    val rdata_w1_b2 = data_way1_bank2.read(current_index)
    val rdata_w1_b3 = data_way1_bank3.read(current_index)

    // 给 CPU 的 32 位数据 (根据 offset 挑选 Bank)
    val way0_word = MuxLookup(req_offset(3,2), 0.U)(Seq(
        0.U -> rdata_w0_b0.asUInt,
        1.U -> rdata_w0_b1.asUInt,
        2.U -> rdata_w0_b2.asUInt,
        3.U -> rdata_w0_b3.asUInt
    ))
    val way1_word = MuxLookup(req_offset(3,2), 0.U)(Seq(
        0.U -> rdata_w1_b0.asUInt,
        1.U -> rdata_w1_b1.asUInt,
        2.U -> rdata_w1_b2.asUInt,
        3.U -> rdata_w1_b3.asUInt
    ))

    // 命中了哪路，就把哪路的数据给 CPU
    io.cpu.rdata := Mux(refill_bypass_match, io.axi.ret_data, Mux(way0_hit, way0_word, way1_word))
    // （高级优化：如果是 REFILL 阶段且 ret_valid，这里还可以直接 Bypass AXI 的返回数据 io.axi.ret_data）

    // 5.2 AXI 读请求 (向主存要数据)
    io.axi.rd_req  := (main_state === sReplace) // 只有在 REPLACE 状态才拉高读请求
    io.axi.rd_type := 3.U // 3 代表 Burst 传输 16 字节
    io.axi.rd_addr := Cat(req_tag, req_index, 0.U(4.W)) // 缺失的物理地址，末 4 位清零对齐

    // 5.3 AXI 写请求 (踢掉脏数据) - 【修复：只写回 V=1 且 D=1 的行】
    // 提取 Victim（被替换行）的 V 位和 D 位 (此时 current_index 已经是 req_index)
    val victim_v = Mux(miss_replace_way === 0.U, way0_v, way1_v)
    val victim_d = Mux(miss_replace_way === 0.U, dirty_way0(req_index), dirty_way1(req_index))
    val need_writeback = victim_v && victim_d // 只有既有效又脏，才需要写回！

    // 5.3 AXI 写请求 (踢掉脏数据)
    val axi_wr_req_reg = RegInit(false.B)
    when(main_state === sMiss && io.axi.wr_rdy && need_writeback) {
        axi_wr_req_reg := true.B  // 只有需要写回，才拉高写请求
    } .elsewhen(io.axi.wr_rdy || !need_writeback) {
        axi_wr_req_reg := false.B
    }
    
    // 如果不需要写回，wr_req 永远是 false，AXI 转接桥会直接忽略写操作
    io.axi.wr_req  := axi_wr_req_reg
    io.axi.wr_type := 3.U 
    // 拼接 128 位的写回数据 (这里用到了你在 REPLACE 阶段读出的那一路的所有 Bank 数据)
    // 物理地址：被踢掉的那个行的原 Tag + Index + 0000
    val replace_tag = Mux(miss_replace_way === 0.U, way0_tag, way1_tag)
    io.axi.wr_addr  := Cat(replace_tag, req_index, 0.U(4.W))
    io.axi.wr_wstrb := "hffff".U 
    // 这里需要将选中的那一路的 4 个 Bank 拼接成 128 位
    // 拼接 128 位的写回数据 (Cat 是高位在左，低位在右，所以从 bank3 拼到 bank0)
    val way0_line = Cat(rdata_w0_b3.asUInt, rdata_w0_b2.asUInt, rdata_w0_b1.asUInt, rdata_w0_b0.asUInt)
    val way1_line = Cat(rdata_w1_b3.asUInt, rdata_w1_b2.asUInt, rdata_w1_b1.asUInt, rdata_w1_b0.asUInt)
    io.axi.wr_data := Mux(miss_replace_way === 0.U, way0_line, way1_line)

    // 5.4 SRAM 写使能控制 (完全翻译书本 表 10.5)
    
    // TagV 表写使能：只有在 REFILL 阶段，且 AXI 传回了最后一次数据 (ret_last) 时才写
    val tagv_we = (main_state === sRefill) && io.axi.ret_valid && io.axi.ret_last
    when(tagv_we) {
        val new_tagv = Cat(req_tag, 1.U(1.W)) // Tag + V位(1)
        when(miss_replace_way === 0.U) { tagv_way0.write(req_index, new_tagv) }
        .otherwise                     { tagv_way1.write(req_index, new_tagv) }
    }

    // Dirty 表写使能：
    // 情况1：命中写 (Hit Write)，把它变脏 (1)
    // 情况2：Refill 完成，根据当前指令是不是 Store 决定它是脏 (1) 还是干净 (0)
    when(wb_state === wbWrite) {
        when(wb_way === 0.U) { dirty_way0(wb_index) := true.B }
        .otherwise           { dirty_way1(wb_index) := true.B }
    }
    when(tagv_we) { // 复用 REFILL 结束的条件
        when(miss_replace_way === 0.U) { dirty_way0(req_index) := req_op }
        .otherwise                     { dirty_way1(req_index) := req_op }
    }

    // 5.4 终极 Data Bank 写入路由 (解决多端口报错 & Store Miss 丢数据)
    
    // 解析 AXI 返回的数据和 Write Buffer 的数据
    val axi_data_vec = VecInit(io.axi.ret_data(7,0), io.axi.ret_data(15,8), io.axi.ret_data(23,16), io.axi.ret_data(31,24))
    val wb_data_vec  = VecInit(wb_wdata(7,0), wb_wdata(15,8), wb_wdata(23,16), wb_wdata(31,24))

    // 各种写状态的判断
    val is_refill = (main_state === sRefill) && io.axi.ret_valid
    val is_hit_write = (wb_state === wbWrite)
    
    // 【修复 Store Miss 丢数据】：如果正在 Refill，且当前是 Store 指令，且 AXI 返回的字刚好是我们要写的那个字！
    val is_target_word = (miss_ret_count === req_offset(3,2))
    val store_miss_merge = is_refill && (req_op === true.B) && is_target_word

    // 为 8 个 Bank 准备统一的控制线
    for (way <- 0 until 2) {
        for (bank <- 0 until 4) {
            val we   = WireDefault(false.B)
            val addr = WireDefault(0.U(8.W))
            val data = WireDefault(VecInit(Seq.fill(4)(0.U(8.W))))
            val mask = WireDefault(VecInit(Seq.fill(4)(false.B)))

            // 情况 1: Hit Write 写入
            when(is_hit_write && (wb_way === way.U) && (wb_offset(3,2) === bank.U)) {
                we   := true.B
                addr := wb_index
                data := wb_data_vec
                mask := VecInit(wb_wstrb(0), wb_wstrb(1), wb_wstrb(2), wb_wstrb(3))
            }
            // 情况 2: Refill 写入
            .elsewhen(is_refill && (miss_replace_way === way.U) && (miss_ret_count === bank.U)) {
                we   := true.B
                addr := req_index
                
                // 【核心合并逻辑】：如果是 Store Miss，并且到了目标字，用 wstrb 掩码决定是用 req_wdata 还是 AXI 的数据
                for (i <- 0 until 4) {
                    when(store_miss_merge && req_wstrb(i)) {
                        data(i) := wb_data_vec(i) // 截胡！用 CPU 要写的数据
                    } .otherwise {
                        data(i) := axi_data_vec(i) // 否则老老实实用主存的数据
                    }
                    mask(i) := true.B // Refill 期间 4 个字节全写
                }
            }

            // 统一调用唯一的一次 .write()
            val target_ram = (way, bank) match {
                case (0, 0) => data_way0_bank0
                case (0, 1) => data_way0_bank1
                case (0, 2) => data_way0_bank2
                case (0, 3) => data_way0_bank3
                case (1, 0) => data_way1_bank0
                case (1, 1) => data_way1_bank1
                case (1, 2) => data_way1_bank2
                case (1, 3) => data_way1_bank3
            }
            when(we) {
                target_ram.write(addr, data, mask)
            }
        }
    }

}
```


#### Short summary: 

empty definition using pc, found symbol in pc: 