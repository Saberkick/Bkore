package mycpu

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import chisel3.util.experimental.loadMemoryFromFileInline
import firrtl.annotations.MemoryLoadFileType

// CPU 与 Cache 的交互接口 (参考书本表 10.2)
class CacheToCpuIO extends Bundle {
    val valid  = Input(Bool())
    val op     = Input(Bool()) //0: Read, 1: Write
    val index  = Input(UInt(8.W))
    val tag    = Input(UInt(20.W)) // 来自 TLB 的物理 Tag，与 index 同拍到达
    val offset = Input(UInt(4.W))
    val wstrb  = Input(UInt(4.W))
    val wdata  = Input(UInt(32.W))

    val addr_ok = Output(Bool())
    val data_ok = Output(Bool())
    val rdata   = Output(UInt(32.W))

    val uncached = Input(Bool())
    val cacop_en   = Input(Bool())
    val cacop_op   = Input(UInt(2.W)) // Cache 只需要高 2 位动作码，低位在流水线分发时已经用掉了
}


// Cache 与 AXI 转接桥的交互接口 (参考书本表 10.3)
class CacheToAxiIO extends Bundle {
    val rd_req    = Output(Bool())
    val rd_type   = Output(UInt(3.W))  // 4 代表 16 字节 Cache 行 (3b'100)
    val rd_addr   = Output(UInt(32.W))
    val rd_rdy    = Input(Bool())
    val ret_valid = Input(Bool())
    val ret_last  = Input(Bool())
    val ret_data  = Input(UInt(32.W))  // AXI 每次只传回来 32 位

    val wr_req    = Output(Bool())
    val wr_type   = Output(UInt(3.W))  // 4 代表 16 字节 Cache 行
    val wr_addr   = Output(UInt(32.W))
    val wr_wstrb  = Output(UInt(4.W))
    val wr_data   = Output(UInt(128.W))// 写回时，一口气吐出 128 位
    val wr_rdy    = Input(Bool())
}

class Cache extends Module {
    val io = IO(new Bundle {
        val cpu = new CacheToCpuIO()
        val axi = new CacheToAxiIO()
    })
    // 一个参数固定、供 I/D 两端复用的阻塞式二路组相联 Cache：
    //   256 sets * 2 ways * 16 B/line = 8192 B
    // 地址格式为 tag[31:12] / index[11:4] / offset[3:0]，每行分成 4 个 32-bit bank。
    // DCache 采用 write-back + write-allocate，dirty victim 先写回再 refill；ICache 只读，
    // 因而正常路径不会产生 dirty write-back。一次只保存一个 miss 上下文。
    //Main FSM

    //If cache hit
    //Idle -> Lookup -> Idle
    //Idle -> Lookup -> Lookup -> ...
    //If cache missed
    //Idle -> Lookup -> Miss -> Replace -> Refill

    //In Idle stage we receive and latch a req from CPU, and assert the addr_ok signal
    //In Lookup stage, if cache hit we assert data_ok and receive a new req just like Idle stage
    //  But if cache miss, we cannot receive a new req, the missed req will be stored safely in Req Buffer
    //  If the cache is full, in miss stage we have to evict a n old Cache line.
    //  So at the same time, we generate a random number (0,1) to pick a victim Way,
    //  take a snapshot of the victim's Valid and Tag and turn to Miss stage.
    //In Miss stage, we wait for the AXI write channel to be ready (wr_rdy == 1),
    //  We also need 1 cycle to let SRAM read the full 128-bit cache line if we need to write dirty data.
    //  So even if wr_rdy is already 1, we still need to wait for a cycle
    //In Replace stage, we got the dirty data and send them outside directly.
    //  We continuously assert rd_req, attempting to read from AXI to get the missed data
    //  We must wait here until the AXI read channel is ready.
    //  Once rd_rdy == 1, we clear the return counter and move to Refill.
    //In Refill stage, We count the returning words.
    //  If the returning word matches our req_offset, we can bypass it directly to the CPU.
    //  Once the last word arrives (ret_last == 1), we write the full 128-bit line
    //  and the new Tag/Valid into SRAM, and return to Idle.
    val sIdle :: sLookup :: sMiss :: sReplace :: sRefill :: Nil = Enum(5)
    val main_state = RegInit(sIdle)

    //Write Buffer FSM
    val wbIdle :: wbWrite :: Nil = Enum(2)
    val wb_state = RegInit(wbIdle)

    // SyncReadMem 使 tag/data 查询至少跨一拍：Idle 接收地址，Lookup 比较两路 tag。
    // 命中 store 先进入单项写缓冲，减少数据 RAM 写端口对下一次查询的阻塞。
    //Data Structure
    //TagV 20:1 Tag, 0 Valid
    val tagv_way0 = SyncReadMem(256, UInt(21.W))
    val tagv_way1 = SyncReadMem(256, UInt(21.W))
    loadMemoryFromFileInline(tagv_way0, "zero_init.hex", MemoryLoadFileType.Hex)
    loadMemoryFromFileInline(tagv_way1, "zero_init.hex", MemoryLoadFileType.Hex)
    // Do not rely on simulator/synthesis memory-file macros for validity.
    // Chiplab does not define ENABLE_INITIAL_MEM_, so the TagV BRAM contents
    // start as X in behavioral simulation.  Resettable valid bits keep tags
    // invalid until a real refill installs them.
    val valid_way0 = RegInit(VecInit(Seq.fill(256)(false.B)))
    val valid_way1 = RegInit(VecInit(Seq.fill(256)(false.B)))
    //Data Bank
    val data_way0_bank0 = SyncReadMem(256, Vec(4, UInt(8.W)))
    val data_way0_bank1 = SyncReadMem(256, Vec(4, UInt(8.W)))
    val data_way0_bank2 = SyncReadMem(256, Vec(4, UInt(8.W)))
    val data_way0_bank3 = SyncReadMem(256, Vec(4, UInt(8.W)))

    val data_way1_bank0 = SyncReadMem(256, Vec(4, UInt(8.W)))
    val data_way1_bank1 = SyncReadMem(256, Vec(4, UInt(8.W)))
    val data_way1_bank2 = SyncReadMem(256, Vec(4, UInt(8.W)))
    val data_way1_bank3 = SyncReadMem(256, Vec(4, UInt(8.W)))
    //Dirty
    val dirty_way0 = RegInit(VecInit(Seq.fill(256)(false.B)))
    val dirty_way1 = RegInit(VecInit(Seq.fill(256)(false.B)))

    //Buffers
    //Request Buffer
    val req_op     = RegInit(false.B)
    val req_index  = RegInit(0.U(8.W))
    val req_tag    = RegInit(0.U(20.W))
    val req_offset = RegInit(0.U(4.W))
    val req_wstrb  = RegInit(0.U(4.W))
    val req_wdata  = RegInit(0.U(32.W))

    // ★ 新增：CACOP 锁存器
    val req_cacop_en = RegInit(false.B)
    val req_cacop_op = RegInit(0.U(2.W))

    //Miss Buffer
    val miss_victim_v    = RegInit(false.B)
    val miss_victim_tag  = RegInit(0.U(20.W))
    val miss_replace_way = RegInit(false.B)
    val miss_ret_count   = RegInit(0.U(2.W))
    //Write Buffer
    val wb_way   = RegInit(false.B)
    val wb_index = RegInit(0.U(8.W))
    val wb_offset= RegInit(0.U(4.W))
    val wb_wstrb = RegInit(0.U(4.W))
    val wb_wdata = RegInit(0.U(32.W))
    //Uncached
    val req_uncached = RegInit(false.B)

    //LFSR Random Generator
    val lfsr = RegInit("hACE1".U(16.W)) //just a seed
    lfsr := Cat(lfsr(14, 0), lfsr(15) ^ lfsr(13) ^ lfsr(12) ^ lfsr(10))
    val random_way = lfsr(0)


    //Read TagV
    val curr_idx = Mux(main_state === sIdle || main_state === sLookup, io.cpu.index, req_index)
    val tagv_rdata_way0 = tagv_way0.read(curr_idx)
    val tagv_rdata_way1 = tagv_way1.read(curr_idx)
    val tagv_read_idx = RegNext(curr_idx, 0.U)
    //In Lookup stage
    val way0_v   = valid_way0(tagv_read_idx)
    val way0_tag = tagv_rdata_way0(20, 1)
    val way0_hit = (!req_uncached || req_cacop_en) && way0_v === 1.U && (way0_tag === req_tag)

    val way1_v   = valid_way1(tagv_read_idx)
    val way1_tag = tagv_rdata_way1(20, 1)
    val way1_hit = (!req_uncached || req_cacop_en) && way1_v === 1.U && (way1_tag === req_tag)
    val cache_hit = (way0_hit || way1_hit) && (!req_uncached || req_cacop_en)

    //Req Buffer Latch
    val is_lookup_write = (main_state === sLookup) && (req_op === true.B)

    //If new addr (Both Idx and Offset) is the same as the current write addr, the inst has to be stalled
    // 修改后：
    val hazard_with_lookup = is_lookup_write && (io.cpu.index === req_index) && (
                            io.cpu.cacop_en || (io.cpu.offset(3,2) === req_offset(3,2)))
    // 修改后：如果是 CACOP，只比对 index；普通访存才精确到 offset
    val hazard_with_wb = wb_state === wbWrite && (io.cpu.index === wb_index) && (
                         io.cpu.cacop_en || (io.cpu.offset(3,2) === wb_offset(3,2)))
    val hit_write_hazard = (hazard_with_lookup || hazard_with_wb)


    val hit_dirty = cache_hit && Mux(way1_hit, dirty_way1(req_index), dirty_way0(req_index))

    // ★ 新增：针对“索引型 CACOP”，计算目标路是否有效且脏
    val target_way_v = Mux(req_offset(0) === 1.U, way1_v, way0_v)
    val target_way_d = Mux(req_offset(0) === 1.U, dirty_way1(req_index), dirty_way0(req_index))
    val target_way_tag = Mux(req_offset(0) === 1.U, way1_tag, way0_tag)
    val index_needs_wb = target_way_v && target_way_d

    // 修改 accept 条件：如果 op=1 且需要写回，不能在当拍结束
    val lookup_accept_normal = !req_cacop_en && cache_hit && (!req_op || (req_op && !hit_write_hazard))
    val lookup_accept_cacop  = req_cacop_en && (
        (req_cacop_op === 0.U) ||
        (req_cacop_op === 1.U && !index_needs_wb) ||
        (req_cacop_op === 2.U && !hit_dirty)
    )
    //addr_ok
    // ★ 修改 addr_ok，允许 CACOP 强行进入
    // 将原有的 addr_ok 赋值替换为：
    val can_accept_new = !hit_write_hazard

    io.cpu.addr_ok := can_accept_new && (
                    (main_state === sIdle) ||
                    (main_state === sLookup && (lookup_accept_normal || lookup_accept_cacop))
                )
    when(io.cpu.valid && io.cpu.addr_ok) {
        req_op     := io.cpu.op
        req_index  := io.cpu.index
        req_tag    := io.cpu.tag
        req_offset := io.cpu.offset
        req_wstrb  := io.cpu.wstrb
        req_wdata  := io.cpu.wdata
        req_uncached := io.cpu.uncached

        // ★ 新增：锁存 CACOP 信号
        req_cacop_en := io.cpu.cacop_en
        req_cacop_op := io.cpu.cacop_op
    }
    //data_ok
    //In Lookup stage, if cache hits or the req is a store inst (), we assert data_ok
    //In refill stage, if the returning word from AXI exactly matches the requested word offset,
    //  we assert data_ok to unblock CPU one step earlier.
    val refill_bypass_match = (main_state === sRefill) && io.axi.ret_valid &&
                              ((miss_ret_count === req_offset(3,2)) || req_uncached) && (req_op === false.B) //only Read need a new data_ok
    //"Prepare" to write victim cache line in Miss state
    val need_read = !req_uncached || (req_uncached && req_op === false.B)
    val victim_v = miss_victim_v
    val victim_d = Mux(miss_replace_way === 0.U, dirty_way0(req_index), dirty_way1(req_index))
    // 修改后：将 CACOP 从 Uncached 的逻辑中彻底摘除
    val is_normal_uncached = req_uncached && !req_cacop_en
    val need_writeback = (!is_normal_uncached && miss_victim_v && victim_d) || (is_normal_uncached && req_op === true.B)

    val axi_wr_req_reg = RegInit(false.B)
    // Once the bridge accepts a write request it may become busy before the
    // read channel is available.  Remember that acceptance by keeping the
    // request bit cleared; otherwise sReplace can wait forever for a second
    // wr_rdy pulse and repeatedly issue an unacknowledged refill read.
    val writeback_done = !need_writeback || !axi_wr_req_reg
    // ★ 新增：CACOP 的完成条件
    // 修改前：(req_cacop_op === 0.U || req_cacop_op === 1.U)
    // 修改后：
    val cacop_index_done = (main_state === sLookup) && req_cacop_en && (
        (req_cacop_op === 0.U) ||
        (req_cacop_op === 1.U && !index_needs_wb)
    )
    val cacop_hit_inval_done = (main_state === sLookup) && req_cacop_en && (req_cacop_op === 2.U) && !hit_dirty
    val cacop_writeback_done = (main_state === sReplace) && req_cacop_en && (axi_wr_req_reg && io.axi.wr_rdy)

    io.cpu.data_ok := (main_state === sLookup && cache_hit && !req_cacop_en) ||
                      (main_state === sLookup && req_op === true.B && !req_cacop_en) ||
                      refill_bypass_match ||
                      cacop_index_done || cacop_hit_inval_done || cacop_writeback_done


    //Main FSM
    switch(main_state) {
        is(sIdle) {
            when(io.cpu.valid && !hit_write_hazard) { main_state := sLookup }
        }
        is(sLookup) {
            when(req_cacop_en) {
                // ★ 修正：判断是否需要背靠背处理下一个请求
                val next_state_if_done = Mux(io.cpu.valid && !hit_write_hazard, sLookup, sIdle)

                when(req_cacop_op === 0.U) {
                    main_state := next_state_if_done
                } .elsewhen(req_cacop_op === 1.U) {
                    when(!index_needs_wb) {
                        main_state := next_state_if_done
                    } .otherwise {
                        // ★ 核心修复：索引作废遇到了脏数据，转去写回！
                        main_state := sMiss
                        miss_replace_way := req_offset(0)
                        miss_victim_v    := true.B
                        miss_victim_tag  := target_way_tag
                    }
                } .elsewhen(req_cacop_op === 2.U) {
                    when(!hit_dirty) {
                        main_state := next_state_if_done
                    } .otherwise {
                        main_state := sMiss
                        miss_replace_way := Mux(way1_hit, 1.U, 0.U)
                        miss_victim_v    := true.B
                        miss_victim_tag  := req_tag
                    }
                }
            } .elsewhen(cache_hit) {
                when(io.cpu.valid && !hit_write_hazard) { main_state := sLookup }
                .otherwise {main_state := sIdle}
            } .otherwise {
                main_state := sMiss
                miss_replace_way := random_way
                val is_safe_to_read = !req_uncached && !req_cacop_en
                miss_victim_v    := Mux(is_safe_to_read, Mux(random_way === 0.U, way0_v, way1_v), false.B)
                miss_victim_tag  := Mux(is_safe_to_read, Mux(random_way === 0.U, way0_tag, way1_tag), 0.U(20.W))
            }
        }
        is(sMiss) {
            when(io.axi.wr_rdy) { main_state := sReplace }
        }
        is(sReplace) {
            // Only request the refill after a required victim write has been
            // accepted and axi_wr_req_reg has been cleared.
            when(writeback_done) {
                when(!need_read || req_cacop_en) {
                    main_state := sIdle
                } .elsewhen(io.axi.rd_rdy) {
                    main_state := sRefill
                    miss_ret_count := 0.U
                }
            }
        }
        is(sRefill) {
            when(io.axi.ret_valid) {
                miss_ret_count := miss_ret_count + 1.U
                when(io.axi.ret_last) {main_state := sIdle}
            }
        }
    }
    //Write Buffer FSM
    switch(wb_state) {
        is(wbIdle) {
            when(is_lookup_write && cache_hit && !req_uncached) {
                wb_state := wbWrite
                wb_way   := Mux(way1_hit, true.B, false.B)
                wb_index := req_index
                wb_offset:= req_offset
                wb_wstrb := req_wstrb
                wb_wdata := req_wdata
            }
        }
        is(wbWrite) {
            when(is_lookup_write && cache_hit) {
                wb_state := wbWrite
                wb_way   := Mux(way1_hit, true.B, false.B)
                wb_index := req_index
                wb_offset:= req_offset
                wb_wstrb := req_wstrb
                wb_wdata := req_wdata
            }
            .otherwise {wb_state := wbIdle}
        }
    }

    //Read the way Data
    val curr_offset = Mux(main_state === sIdle || main_state === sLookup, io.cpu.offset, req_offset)
    val is_reading_state = (main_state === sIdle) || (main_state === sLookup)
    val is_miss_state    = (main_state === sMiss)
    val ren_b0 = is_miss_state || (is_reading_state && curr_offset(3,2) === 0.U)
    val ren_b1 = is_miss_state || (is_reading_state && curr_offset(3,2) === 1.U)
    val ren_b2 = is_miss_state || (is_reading_state && curr_offset(3,2) === 2.U)
    val ren_b3 = is_miss_state || (is_reading_state && curr_offset(3,2) === 3.U)

    val rdata_w0_b0 = data_way0_bank0.read(curr_idx, ren_b0)
    val rdata_w0_b1 = data_way0_bank1.read(curr_idx, ren_b1)
    val rdata_w0_b2 = data_way0_bank2.read(curr_idx, ren_b2)
    val rdata_w0_b3 = data_way0_bank3.read(curr_idx, ren_b3)

    val rdata_w1_b0 = data_way1_bank0.read(curr_idx, ren_b0)
    val rdata_w1_b1 = data_way1_bank1.read(curr_idx, ren_b1)
    val rdata_w1_b2 = data_way1_bank2.read(curr_idx, ren_b2)
    val rdata_w1_b3 = data_way1_bank3.read(curr_idx, ren_b3)

    //To CPU
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

    //Added the refill bypass match
    io.cpu.rdata := Mux(refill_bypass_match, io.axi.ret_data, Mux(way0_hit, way0_word, way1_word))

    //Require missed cache data in Replace state

    io.axi.rd_req  := (main_state === sReplace) && writeback_done && need_read && !req_cacop_en
    io.axi.rd_type := Mux(req_uncached, 2.U, 4.U)
    io.axi.rd_addr := Cat(req_tag, req_index, Mux(req_uncached, req_offset, 0.U(4.W)))


    val miss_to_replace = (main_state === sMiss) && io.axi.wr_rdy
    when(miss_to_replace && need_writeback) {
        axi_wr_req_reg := true.B
    } .elsewhen(axi_wr_req_reg && io.axi.wr_rdy) {
        axi_wr_req_reg := false.B
    }
    io.axi.wr_req  := axi_wr_req_reg    //It will be activated in Replace state, actually
    io.axi.wr_type := Mux(is_normal_uncached, 2.U, 4.U)

    //Send old victim cache line data to AXI
    val replace_tag = miss_victim_tag
    io.axi.wr_addr  := Mux(is_normal_uncached, Cat(req_tag, req_index, req_offset), Cat(replace_tag, req_index, 0.U(4.W)))
    io.axi.wr_wstrb := Mux(is_normal_uncached, req_wstrb, "hf".U)

    val way0_line = Cat(rdata_w0_b3.asUInt, rdata_w0_b2.asUInt, rdata_w0_b1.asUInt, rdata_w0_b0.asUInt)
    val way1_line = Cat(rdata_w1_b3.asUInt, rdata_w1_b2.asUInt, rdata_w1_b1.asUInt, rdata_w1_b0.asUInt)
    val uncache_wdata_128 = Fill(4, req_wdata)

    io.axi.wr_data  := Mux(is_normal_uncached, uncache_wdata_128, Mux(miss_replace_way === 0.U, way0_line, way1_line))

    //RAM Write Enable Control
    //For TagV, Only in Refill state and AXI send back the last byte (ret_last) we write the SRAM
    val tagv_we = (main_state === sRefill) && io.axi.ret_valid && io.axi.ret_last && !req_uncached
    // ★ 新增：CACOP 作废触发条件
    // 1. 在 Lookup 阶段直接作废的条件 (索引型，或命中干净数据)
    // 将原有的 cacop_inval_way0 和 way1 替换为：
    // 将原先条件里的 (req_cacop_op === 0.U || req_cacop_op === 1.U) 拆开：
    val cacop_inval_way0 = (main_state === sLookup) && req_cacop_en && (
        (req_cacop_op === 0.U && req_offset(0) === 0.U) ||
        (req_cacop_op === 1.U && req_offset(0) === 0.U && !index_needs_wb) ||
        (req_cacop_op === 2.U && way0_hit && !hit_dirty)
    )

    val cacop_inval_way1 = (main_state === sLookup) && req_cacop_en && (
        (req_cacop_op === 0.U && req_offset(0) === 1.U) ||
        (req_cacop_op === 1.U && req_offset(0) === 1.U && !index_needs_wb) ||
        (req_cacop_op === 2.U && way1_hit && !hit_dirty)
    )

    // 2. 在 Replace 阶段作废的条件 (命中脏数据，写回完成后作废)
    // 利用你已经写好的 cacop_writeback_done 信号！
    val cacop_wb_inval_way0 = cacop_writeback_done && (miss_replace_way === 0.U)
    val cacop_wb_inval_way1 = cacop_writeback_done && (miss_replace_way === 1.U)

    // 综合写使能
    val tagv0_we = (tagv_we && miss_replace_way === 0.U) || cacop_inval_way0 || cacop_wb_inval_way0
    val tagv1_we = (tagv_we && miss_replace_way === 1.U) || cacop_inval_way1 || cacop_wb_inval_way1

    // 如果是正常的 Refill，写入新 Tag(V=1)；如果是 CACOP 作废，强行写入 0(V=0)
    val new_tagv = Cat(req_tag, 1.U(1.W))
    when(tagv0_we) {
        tagv_way0.write(req_index, Mux(tagv_we, new_tagv, 0.U(21.W)))
        valid_way0(req_index) := tagv_we
    }
    when(tagv1_we) {
        tagv_way1.write(req_index, Mux(tagv_we, new_tagv, 0.U(21.W)))
        valid_way1(req_index) := tagv_we
    }
    //For dirty, if hit write or in Refill state && current inst is a Store inst, we update it to 1
    when(wb_state === wbWrite) {
        when(wb_way === 0.U) { dirty_way0(wb_index) := true.B }
        .otherwise           { dirty_way1(wb_index) := true.B }
    }
    when(tagv_we) {
        when(miss_replace_way === 0.U) { dirty_way0(req_index) := req_op }
        .otherwise                     { dirty_way1(req_index) := req_op }
    }

    //Data Bank Write
    val axi_data_vec = VecInit(io.axi.ret_data(7,0), io.axi.ret_data(15,8), io.axi.ret_data(23,16), io.axi.ret_data(31,24))
    val wb_data_vec  = VecInit(wb_wdata(7,0), wb_wdata(15,8), wb_wdata(23,16), wb_wdata(31,24))
    val req_data_vec = VecInit(req_wdata(7,0), req_wdata(15,8), req_wdata(23,16), req_wdata(31,24))

    //If cache miss && current inst is a Store inst, we should fill in the newest data
    val is_refill = (main_state === sRefill) && io.axi.ret_valid
    val store_miss_merge = is_refill && (req_op === true.B) && (miss_ret_count === req_offset(3,2))


    for (way <- 0 until 2) {
        for (bank <- 0 until 4) {
            val we   = WireDefault(false.B)
            val addr = WireDefault(0.U(8.W))
            val data = WireDefault(VecInit(Seq.fill(4)(0.U(8.W))))
            val mask = WireDefault(VecInit(Seq.fill(4)(false.B)))
            //Hit Write
            when((wb_state === wbWrite) && (wb_way === way.U) && (wb_offset(3,2) === bank.U)) {
                we   := true.B
                addr := wb_index
                data := wb_data_vec
                mask := VecInit(wb_wstrb(0), wb_wstrb(1), wb_wstrb(2), wb_wstrb(3))
            }
            //Refill Write
            .elsewhen(is_refill && (miss_replace_way === way.U) && (miss_ret_count === bank.U) && !req_uncached) {
                we   := true.B
                addr := req_index
                //Store Miss Merge
                for (i <- 0 until 4) {
                    when(store_miss_merge && req_wstrb(i)) { data(i) := req_data_vec(i)}
                    .otherwise { data(i) := axi_data_vec(i) }
                    mask(i) := true.B
                }
            }
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
