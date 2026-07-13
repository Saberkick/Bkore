error id: file://<WORKSPACE>/src/main/scala/mycpu/Top.scala:waddr
file://<WORKSPACE>/src/main/scala/mycpu/Top.scala
empty definition using pc, found symbol in pc: waddr
empty definition using semanticdb
empty definition using fallback
non-local guesses:
	 -chisel3/regfile/io/waddr.
	 -chisel3/regfile/io/waddr#
	 -chisel3/regfile/io/waddr().
	 -chisel3/util/regfile/io/waddr.
	 -chisel3/util/regfile/io/waddr#
	 -chisel3/util/regfile/io/waddr().
	 -regfile/io/waddr.
	 -regfile/io/waddr#
	 -regfile/io/waddr().
	 -scala/Predef.regfile.io.waddr.
	 -scala/Predef.regfile.io.waddr#
	 -scala/Predef.regfile.io.waddr().
offset: 7784
uri: file://<WORKSPACE>/src/main/scala/mycpu/Top.scala
text:
```scala
package mycpu

import chisel3._
import chisel3.util._

class mycpu_top extends RawModule {
    // 龙芯 AXI 标准顶层时钟与复位命名
    val aclk    = IO(Input(Clock()))
    val aresetn = IO(Input(Bool()))

    // ==========================================
    // 替换为 AXI 总线接口 (严格遵循表 8.4)
    // ==========================================
    // 1. 读地址通道 (AR)
    val arid    = IO(Output(UInt(4.W)))
    val araddr  = IO(Output(UInt(32.W)))
    val arlen   = IO(Output(UInt(8.W)))
    val arsize  = IO(Output(UInt(3.W)))
    val arburst = IO(Output(UInt(2.W)))
    val arlock  = IO(Output(UInt(2.W)))
    val arcache = IO(Output(UInt(4.W)))
    val arprot  = IO(Output(UInt(3.W)))
    val arvalid = IO(Output(Bool()))
    val arready = IO(Input(Bool()))

    // 2. 读响应通道 (R)
    val rid     = IO(Input(UInt(4.W)))
    val rdata   = IO(Input(UInt(32.W)))
    val rresp   = IO(Input(UInt(2.W)))
    val rlast   = IO(Input(Bool()))
    val rvalid  = IO(Input(Bool()))
    val rready  = IO(Output(Bool()))

    // 3. 写地址通道 (AW)
    val awid    = IO(Output(UInt(4.W)))
    val awaddr  = IO(Output(UInt(32.W)))
    val awlen   = IO(Output(UInt(8.W)))
    val awsize  = IO(Output(UInt(3.W)))
    val awburst = IO(Output(UInt(2.W)))
    val awlock  = IO(Output(UInt(2.W)))
    val awcache = IO(Output(UInt(4.W)))
    val awprot  = IO(Output(UInt(3.W)))
    val awvalid = IO(Output(Bool()))
    val awready = IO(Input(Bool()))

    // 4. 写数据通道 (W)
    val wid     = IO(Output(UInt(4.W)))
    val wdata   = IO(Output(UInt(32.W)))
    val wstrb   = IO(Output(UInt(4.W)))
    val wlast   = IO(Output(Bool()))
    val wvalid  = IO(Output(Bool()))
    val wready  = IO(Input(Bool()))

    // 5. 写响应通道 (B)
    val bid     = IO(Input(UInt(4.W)))
    val bresp   = IO(Input(UInt(2.W)))
    val bvalid  = IO(Input(Bool()))
    val bready  = IO(Output(Bool()))

    // trace debug interface
    val debug_wb_pc       = IO(Output(UInt(32.W)))
    val debug_wb_rf_we    = IO(Output(UInt(4.W)))
    val debug_wb_rf_wnum  = IO(Output(UInt(5.W)))
    val debug_wb_rf_wdata = IO(Output(UInt(32.W)))
    
    val reset_high = !aresetn

    withClockAndReset(aclk, reset_high) {

        // 1. 例化所有内部模块
        val ctrl = Module(new Ctrl())
        val timer = Module(new StableCounter())
        val regfile = Module(new Regfile())
        
        val if_stage  = Module(new StageIF())
        val id_stage  = Module(new StageID())
        val ex_stage  = Module(new StageEX())
        val mem_stage = Module(new StageMEM())
        val wb_stage  = Module(new StageWB())

        // ★ 新增：例化 AXI 转接桥
        val bridge = Module(new SramToAxiBridge())

        val tlb_module = Module(new tlb())

        //终于！
        val icache = Module(new Cache())
        val dcache = Module(new Cache())

        if_stage.io.mmu_config := wb_stage.io.mmu_config
        ex_stage.io.mmu_config := wb_stage.io.mmu_config


        // ---------------- IF 到 TLB (Port 0) ----------------
        tlb_module.io.s0_vppn     := if_stage.io.tlb_s0_vppn
        tlb_module.io.s0_va_bit12 := if_stage.io.tlb_s0_va_bit12
        tlb_module.io.s0_asid     := if_stage.io.tlb_s0_asid
        
        if_stage.io.tlb_s0_found  := tlb_module.io.s0_found
        if_stage.io.tlb_s0_ppn    := tlb_module.io.s0_ppn
        if_stage.io.tlb_s0_ps     := tlb_module.io.s0_ps
        if_stage.io.tlb_s0_plv    := tlb_module.io.s0_plv
        if_stage.io.tlb_s0_mat    := tlb_module.io.s0_mat
        if_stage.io.tlb_s0_v      := tlb_module.io.s0_v


        // ---------------- EX 到 TLB (Port 1) ----------------
        tlb_module.io.s1_vppn     := ex_stage.io.tlb_s1_vppn
        tlb_module.io.s1_va_bit12 := ex_stage.io.tlb_s1_va_bit12
        tlb_module.io.s1_asid     := ex_stage.io.tlb_s1_asid
        
        ex_stage.io.tlb_s1_found  := tlb_module.io.s1_found
        ex_stage.io.tlb_s1_index  := tlb_module.io.s1_index
        ex_stage.io.tlb_s1_ppn    := tlb_module.io.s1_ppn
        ex_stage.io.tlb_s1_ps     := tlb_module.io.s1_ps
        ex_stage.io.tlb_s1_plv    := tlb_module.io.s1_plv
        ex_stage.io.tlb_s1_mat    := tlb_module.io.s1_mat
        ex_stage.io.tlb_s1_d      := tlb_module.io.s1_d
        ex_stage.io.tlb_s1_v      := tlb_module.io.s1_v


        
        tlb_module.io.invtlb_valid := ex_stage.io.invtlb_valid
        tlb_module.io.invtlb_op    := ex_stage.io.invtlb_op
        
        // ---------------- WB 级与 TLB 读写连线 ----------------
        tlb_module.io.we      := wb_stage.io.tlb_we
        tlb_module.io.w_index := wb_stage.io.tlb_w_idx
        tlb_module.io.w_e     := wb_stage.io.tlb_w_dat.e
        tlb_module.io.w_vppn  := wb_stage.io.tlb_w_dat.vppn
        tlb_module.io.w_ps    := Mux(wb_stage.io.tlb_w_dat.ps4MB, 21.U, 12.U)
        tlb_module.io.w_asid  := wb_stage.io.tlb_w_dat.asid
        tlb_module.io.w_g     := wb_stage.io.tlb_w_dat.g
        tlb_module.io.w_ppn0  := wb_stage.io.tlb_w_dat.ppn0
        tlb_module.io.w_plv0  := wb_stage.io.tlb_w_dat.plv0
        tlb_module.io.w_mat0  := wb_stage.io.tlb_w_dat.mat0
        tlb_module.io.w_d0    := wb_stage.io.tlb_w_dat.d0
        tlb_module.io.w_v0    := wb_stage.io.tlb_w_dat.v0
        tlb_module.io.w_ppn1  := wb_stage.io.tlb_w_dat.ppn1
        tlb_module.io.w_plv1  := wb_stage.io.tlb_w_dat.plv1
        tlb_module.io.w_mat1  := wb_stage.io.tlb_w_dat.mat1
        tlb_module.io.w_d1    := wb_stage.io.tlb_w_dat.d1
        tlb_module.io.w_v1    := wb_stage.io.tlb_w_dat.v1

        tlb_module.io.r_index := wb_stage.io.tlb_r_idx
        wb_stage.io.tlb_r_dat.e     := tlb_module.io.r_e
        wb_stage.io.tlb_r_dat.ps4MB := tlb_module.io.r_ps === 21.U
        wb_stage.io.tlb_r_dat.vppn  := tlb_module.io.r_vppn
        wb_stage.io.tlb_r_dat.asid  := tlb_module.io.r_asid
        wb_stage.io.tlb_r_dat.g     := tlb_module.io.r_g
        wb_stage.io.tlb_r_dat.ppn0  := tlb_module.io.r_ppn0
        wb_stage.io.tlb_r_dat.plv0  := tlb_module.io.r_plv0
        wb_stage.io.tlb_r_dat.mat0  := tlb_module.io.r_mat0
        wb_stage.io.tlb_r_dat.d0    := tlb_module.io.r_d0
        wb_stage.io.tlb_r_dat.v0    := tlb_module.io.r_v0
        wb_stage.io.tlb_r_dat.ppn1  := tlb_module.io.r_ppn1
        wb_stage.io.tlb_r_dat.plv1  := tlb_module.io.r_plv1
        wb_stage.io.tlb_r_dat.mat1  := tlb_module.io.r_mat1
        wb_stage.io.tlb_r_dat.d1    := tlb_module.io.r_d1
        wb_stage.io.tlb_r_dat.v1    := tlb_module.io.r_v1
        

        // 2. 主数据通路与前递连线 (保持不变)
        if_stage.io.out <> id_stage.io.in
        id_stage.io.out <> ex_stage.io.in
        ex_stage.io.out <> mem_stage.io.in
        mem_stage.io.out <> wb_stage.io.in

        ctrl.io.ex_branch_req := ex_stage.io.branch_req
        ctrl.io.ex_branch_pc  := ex_stage.io.branch_pc
        ctrl.io.wb_flush      := wb_stage.io.wb_flush
        ctrl.io.wb_target_pc  := wb_stage.io.wb_target_pc

        if_stage.io.flush           := ctrl.io.flush_if
        if_stage.io.flush_target_pc := ctrl.io.next_pc
        id_stage.io.flush           := ctrl.io.flush_id
        ex_stage.io.flush           := ctrl.io.flush_ex
        mem_stage.io.flush          := ctrl.io.flush_mem

        id_stage.io.fwdFromEx  := ex_stage.io.fwdOut
        id_stage.io.fwdFromMem := mem_stage.io.fwdOut
        ex_stage.io.fwdFromMem := mem_stage.io.fwdOut
        ex_stage.io.fwdFromWb  := wb_stage.io.fwdOut
        ex_stage.io.mem_has_exc_in := mem_stage.io.mem_has_exc_out

        id_stage.io.has_int   := wb_stage.io.wb_has_int
        wb_stage.io.hw_int_in := 0.U(8.W)
        ex_stage.io.timer_in  := timer.io.timer_out

        regfile.io.raddr1 := id_stage.io.rf_raddr1
        id_stage.io.rf_rdata1 := regfile.io.rdata1
        regfile.io.raddr2 := id_stage.io.rf_raddr2
        id_stage.io.rf_rdata2 := regfile.io.rdata2
        regfile.io.we     := wb_stage.io.rf_we
        regfile.io.wad@@dr  := wb_stage.io.rf_waddr
        regfile.io.wdata  := wb_stage.io.rf_wdata

        // ==========================================
        // 3. 内部连线：把 CPU 的类 SRAM 连给桥的左手
        // ==========================================
        // ★ CACOP 路由解码 (从 EX 级截获)
        val ex_is_cacop = ex_stage.io.fwdOut.valid && ex_stage.io.out.bits.is_cacop
        val ex_cacop_op = ex_stage.io.out.bits.cacop_op
        val is_icache_cacop = ex_is_cacop && (ex_cacop_op(2, 0) === 0.U)
        val is_dcache_cacop = ex_is_cacop && (ex_cacop_op(2, 0) === 1.U)
        val cacop_action    = ex_cacop_op(4, 3)

        // -- 指令端 (IF & ICache) --
        // 硬件级仲裁：EX 级发出的 CACOP 夺取 ICache 控制权，强制 IF 级挂起死等
        icache.io.cpu.valid  := Mux(is_icache_cacop, ex_stage.io.data_sram.req, if_stage.io.inst_sram.req)
        icache.io.cpu.op     := false.B
        val icache_addr      = Mux(is_icache_cacop, ex_stage.io.data_sram.addr, if_stage.io.inst_sram.addr)
        icache.io.cpu.index  := icache_addr(11, 4)
        icache.io.cpu.tag    := icache_addr(31, 12)
        icache.io.cpu.offset := icache_addr(3, 0)
        icache.io.cpu.wstrb  := 0.U
        icache.io.cpu.wdata  := 0.U
        icache.io.cpu.uncached := Mux(is_icache_cacop, ex_stage.io.data_uncached, if_stage.io.inst_uncached)
        
        // 喂给 Cache 的专属 CACOP 信号 (需要确保 CacheToCpuIO 中已定义这两个字段)
        icache.io.cpu.cacop_en := is_icache_cacop && ex_stage.io.data_sram.req
        icache.io.cpu.cacop_op := cacop_action

        // 当 ICache 被 CACOP 抢占时，切断 IF 级的握手，让 IF 级流水线自动阻塞
        if_stage.io.inst_sram.addr_ok := icache.io.cpu.addr_ok && !is_icache_cacop
        if_stage.io.inst_sram.data_ok := icache.io.cpu.data_ok && !is_icache_cacop
        if_stage.io.inst_sram.rdata   := icache.io.cpu.rdata

        // 2. ICache 的 AXI 侧接口直连转接桥
        bridge.io.inst_cache <> icache.io.axi

        // -- 数据端 (EX & DCache) --
        // 如果是 ICache 的 CACOP，DCache 不受影响，正常休眠
        dcache.io.cpu.valid  := ex_stage.io.data_sram.req && !is_icache_cacop
        dcache.io.cpu.op     := ex_stage.io.data_sram.wr
        dcache.io.cpu.index  := ex_stage.io.data_sram.addr(11, 4)
        dcache.io.cpu.tag    := ex_stage.io.data_sram.addr(31, 12)
        dcache.io.cpu.offset := ex_stage.io.data_sram.addr(3, 0)
        dcache.io.cpu.wstrb  := ex_stage.io.data_sram.wstrb
        dcache.io.cpu.wdata  := ex_stage.io.data_sram.wdata
        dcache.io.cpu.uncached := ex_stage.io.data_uncached
        
        dcache.io.cpu.cacop_en := is_dcache_cacop && ex_stage.io.data_sram.req
        dcache.io.cpu.cacop_op := cacop_action

        bridge.io.data_cache <> dcache.io.axi

        // EX 级的反馈握手信号：根据当前 CACOP 的去向，智能接管 addr_ok 和 data_ok
        ex_stage.io.data_sram.addr_ok := Mux(is_icache_cacop, icache.io.cpu.addr_ok, dcache.io.cpu.addr_ok)
        ex_stage.io.data_sram.data_ok := Mux(is_icache_cacop, icache.io.cpu.data_ok, dcache.io.cpu.data_ok)
        ex_stage.io.data_sram.rdata   := dcache.io.cpu.rdata
        
        // 3. 幽灵数据探头：保持盯防 EX 级最终采纳的握手状态
        mem_stage.io.sram_req         := ex_stage.io.data_sram.req
        mem_stage.io.sram_addr_ok     := ex_stage.io.data_sram.addr_ok
        mem_stage.io.sram_data_ok     := ex_stage.io.data_sram.data_ok
        mem_stage.io.sram_rdata       := ex_stage.io.data_sram.rdata

        // ==========================================
        // 4. 外部连线：把桥的右手连给 AXI 物理引脚
        // ==========================================
        arid    := bridge.io.axi.arid
        araddr  := bridge.io.axi.araddr
        arlen   := bridge.io.axi.arlen
        arsize  := bridge.io.axi.arsize
        arburst := bridge.io.axi.arburst
        arlock  := bridge.io.axi.arlock
        arcache := bridge.io.axi.arcache
        arprot  := bridge.io.axi.arprot
        arvalid := bridge.io.axi.arvalid
        bridge.io.axi.arready := arready

        bridge.io.axi.rid     := rid
        bridge.io.axi.rdata   := rdata
        bridge.io.axi.rresp   := rresp
        bridge.io.axi.rlast   := rlast
        bridge.io.axi.rvalid  := rvalid
        rready  := bridge.io.axi.rready

        awid    := bridge.io.axi.awid
        awaddr  := bridge.io.axi.awaddr
        awlen   := bridge.io.axi.awlen
        awsize  := bridge.io.axi.awsize
        awburst := bridge.io.axi.awburst
        awlock  := bridge.io.axi.awlock
        awcache := bridge.io.axi.awcache
        awprot  := bridge.io.axi.awprot
        awvalid := bridge.io.axi.awvalid
        bridge.io.axi.awready := awready

        wid     := bridge.io.axi.wid
        wdata   := bridge.io.axi.wdata
        wstrb   := bridge.io.axi.wstrb
        wlast   := bridge.io.axi.wlast
        wvalid  := bridge.io.axi.wvalid
        bridge.io.axi.wready  := wready

        bridge.io.axi.bid     := bid
        bridge.io.axi.bresp   := bresp
        bridge.io.axi.bvalid  := bvalid
        bready  := bridge.io.axi.bready

        // 5. Debug 接口连线
        debug_wb_pc       := wb_stage.io.debug_wb_pc
        debug_wb_rf_we    := wb_stage.io.debug_wb_rf_we
        debug_wb_rf_wnum  := wb_stage.io.debug_wb_rf_wnum
        debug_wb_rf_wdata := wb_stage.io.debug_wb_rf_wdata
    }
}
```


#### Short summary: 

empty definition using pc, found symbol in pc: waddr