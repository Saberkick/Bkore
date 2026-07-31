package mycpu

import chisel3._

/**
  * Competition-facing top for the new dual-issue core.
  *
  * It uses the competition module name `core_top` and preserves the existing
  * AXI/auxiliary contract while exposing a second commit-debug lane.
  */
class DualCoreTop extends RawModule {
    override def desiredName: String = "core_top"

    val aclk    = IO(Input(Clock()))
    val aresetn = IO(Input(Bool()))
    val intrpt  = IO(Input(UInt(8.W)))

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

    val rid     = IO(Input(UInt(4.W)))
    val rdata   = IO(Input(UInt(32.W)))
    val rresp   = IO(Input(UInt(2.W)))
    val rlast   = IO(Input(Bool()))
    val rvalid  = IO(Input(Bool()))
    val rready  = IO(Output(Bool()))

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

    val wid     = IO(Output(UInt(4.W)))
    val wdata   = IO(Output(UInt(32.W)))
    val wstrb   = IO(Output(UInt(4.W)))
    val wlast   = IO(Output(Bool()))
    val wvalid  = IO(Output(Bool()))
    val wready  = IO(Input(Bool()))

    val bid     = IO(Input(UInt(4.W)))
    val bresp   = IO(Input(UInt(2.W)))
    val bvalid  = IO(Input(Bool()))
    val bready  = IO(Output(Bool()))

    val debug0_wb_pc       = IO(Output(UInt(32.W)))
    val debug0_wb_rf_wen   = IO(Output(UInt(4.W)))
    val debug0_wb_rf_wnum  = IO(Output(UInt(5.W)))
    val debug0_wb_rf_wdata = IO(Output(UInt(32.W)))
    val debug1_wb_pc       = IO(Output(UInt(32.W)))
    val debug1_wb_rf_wen   = IO(Output(UInt(4.W)))
    val debug1_wb_rf_wnum  = IO(Output(UInt(5.W)))
    val debug1_wb_rf_wdata = IO(Output(UInt(32.W)))

    val break_point = IO(Input(Bool()))
    val infor_flag  = IO(Input(Bool()))
    val reg_num     = IO(Input(UInt(5.W)))
    val ws_valid    = IO(Output(Bool()))
    val rf_rdata    = IO(Output(UInt(32.W)))

    val resetHigh = (!aresetn).asAsyncReset
    withClockAndReset(aclk, resetHigh) {
        val frontend = Module(new DualFrontend())
        val backend = Module(new DualBackend())
        val icache = Module(new DualICache())
        val dcache = Module(new Cache())
        val bridge = Module(new SramToAxiBridge())
        val tlbModule = Module(new tlb())
        val timer = Module(new StableCounter())

        backend.io.fetchValid := frontend.io.outValid
        backend.io.fetchBits := frontend.io.outBits
        frontend.io.popCount := backend.io.popCount
        frontend.io.flush := backend.io.frontendFlush
        frontend.io.flushTarget := backend.io.frontendTarget
        frontend.io.flushPredictorHistory := backend.io.flushPredictorHistory
        frontend.io.predictorUpdate := backend.io.predictorUpdate

        frontend.io.cache <> icache.io.cpu
        icache.io.invalidateAll := backend.io.icacheInvalidateAll
        backend.io.dcache <> dcache.io.cpu
        bridge.io.inst_cache <> icache.io.axi
        bridge.io.data_cache <> dcache.io.axi

        frontend.io.mmuConfig := backend.io.mmuConfig
        tlbModule.io.s0_vppn := frontend.io.tlbVppn
        tlbModule.io.s0_va_bit12 := frontend.io.tlbVaBit12
        tlbModule.io.s0_asid := frontend.io.tlbAsid
        frontend.io.tlbFound := tlbModule.io.s0_found
        frontend.io.tlbPpn := tlbModule.io.s0_ppn
        frontend.io.tlbPs := tlbModule.io.s0_ps
        frontend.io.tlbPlv := tlbModule.io.s0_plv
        frontend.io.tlbMat := tlbModule.io.s0_mat
        frontend.io.tlbV := tlbModule.io.s0_v

        tlbModule.io.s1_vppn := backend.io.tlbVppn
        tlbModule.io.s1_va_bit12 := backend.io.tlbVaBit12
        tlbModule.io.s1_asid := backend.io.tlbAsid
        backend.io.tlbFound := tlbModule.io.s1_found
        backend.io.tlbIndex := tlbModule.io.s1_index
        backend.io.tlbPpn := tlbModule.io.s1_ppn
        backend.io.tlbPs := tlbModule.io.s1_ps
        backend.io.tlbPlv := tlbModule.io.s1_plv
        backend.io.tlbMat := tlbModule.io.s1_mat
        backend.io.tlbD := tlbModule.io.s1_d
        backend.io.tlbV := tlbModule.io.s1_v

        tlbModule.io.we := backend.io.tlbWe
        tlbModule.io.w_index := backend.io.tlbWIndex
        tlbModule.io.w_dat := backend.io.tlbWData
        tlbModule.io.r_index := backend.io.tlbRIndex
        backend.io.tlbRData := tlbModule.io.r_dat
        tlbModule.io.invtlb_valid := backend.io.invtlbValid
        tlbModule.io.invtlb_op := backend.io.invtlbOp
        tlbModule.io.invtlb_vppn := backend.io.invtlbVppn
        tlbModule.io.invtlb_asid := backend.io.invtlbAsid

        backend.io.timer := timer.io.timer_out
        backend.io.hwInterrupt := intrpt
        backend.io.inspectAddr := reg_num

        bridge.io.axi.arready := arready
        bridge.io.axi.rid := rid
        bridge.io.axi.rdata := rdata
        bridge.io.axi.rresp := rresp
        bridge.io.axi.rlast := rlast
        bridge.io.axi.rvalid := rvalid
        bridge.io.axi.awready := awready
        bridge.io.axi.wready := wready
        bridge.io.axi.bid := bid
        bridge.io.axi.bresp := bresp
        bridge.io.axi.bvalid := bvalid

        arid := bridge.io.axi.arid
        araddr := bridge.io.axi.araddr
        arlen := bridge.io.axi.arlen
        arsize := bridge.io.axi.arsize
        arburst := bridge.io.axi.arburst
        arlock := bridge.io.axi.arlock
        arcache := bridge.io.axi.arcache
        arprot := bridge.io.axi.arprot
        arvalid := bridge.io.axi.arvalid
        rready := bridge.io.axi.rready

        awid := bridge.io.axi.awid
        awaddr := bridge.io.axi.awaddr
        awlen := bridge.io.axi.awlen
        awsize := bridge.io.axi.awsize
        awburst := bridge.io.axi.awburst
        awlock := bridge.io.axi.awlock
        awcache := bridge.io.axi.awcache
        awprot := bridge.io.axi.awprot
        awvalid := bridge.io.axi.awvalid

        wid := bridge.io.axi.wid
        wdata := bridge.io.axi.wdata
        wstrb := bridge.io.axi.wstrb
        wlast := bridge.io.axi.wlast
        wvalid := bridge.io.axi.wvalid
        bready := bridge.io.axi.bready

        debug0_wb_pc := backend.io.debug(0).pc
        debug0_wb_rf_wen := backend.io.debug(0).wen
        debug0_wb_rf_wnum := backend.io.debug(0).wnum
        debug0_wb_rf_wdata := backend.io.debug(0).wdata
        debug1_wb_pc := backend.io.debug(1).pc
        debug1_wb_rf_wen := backend.io.debug(1).wen
        debug1_wb_rf_wnum := backend.io.debug(1).wnum
        debug1_wb_rf_wdata := backend.io.debug(1).wdata

        ws_valid := backend.io.debug(0).wen.orR || backend.io.debug(1).wen.orR
        rf_rdata := backend.io.inspectData
    }
}
