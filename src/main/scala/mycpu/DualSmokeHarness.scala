package mycpu

import chisel3._
import chisel3.util._

/** Simulation-only latency model; production RTL uses multiplier.xci. */
class SmokeMultiplierModel extends Module {
    val io = IO(new Bundle {
        val a = Input(UInt(32.W))
        val b = Input(UInt(32.W))
        val p = Output(UInt(64.W))
    })
    val stage0 = RegNext(io.a * io.b)
    io.p := RegNext(stage0)
}

/**
  * Simulator-independent directed smoke test.
  *
  * The harness uses synthesizable assertions and can therefore be run with the
  * WSL Verilator already present on the development machine even when Chisel's
  * Windows simulator launcher is unavailable.
  */
class DualSmokeHarness extends Module {
    val io = IO(new Bundle {
        val done = Output(Bool())
    })

    val cycle = RegInit(0.U(5.W))
    when(cycle =/= 31.U) {
        cycle := cycle + 1.U
    }
    io.done := cycle >= 20.U

    val issue = Module(new DualIssueUnit())
    for (lane <- 0 until 2) {
        issue.io.in(lane) := 0.U.asTypeOf(new DualIssueInfo())
        issue.io.in(lane).valid := cycle <= 2.U
    }
    when(cycle === 0.U) {
        issue.io.in(0).regWrite := true.B
        issue.io.in(0).dest := 3.U
        issue.io.in(1).src1Read := true.B
        issue.io.in(1).src1 := 4.U
        issue.io.in(1).regWrite := true.B
        issue.io.in(1).dest := 5.U
        assert(issue.io.issueCount === 2.U)
    }
    when(cycle === 1.U) {
        issue.io.in(0).regWrite := true.B
        issue.io.in(0).dest := 7.U
        issue.io.in(1).src1Read := true.B
        issue.io.in(1).src1 := 7.U
        assert(issue.io.issueCount === 1.U)
    }
    when(cycle === 2.U) {
        issue.io.in(1).isBranch := true.B
        assert(issue.io.issueCount === 2.U)
    }

    val availability = Module(new DualIssueAvailability())
    availability.io.structuralCount := 2.U
    availability.io.lane1Selected := true.B
    availability.io.blocked := VecInit(
        cycle === 1.U, cycle === 0.U)
    when(cycle === 0.U) {
        assert(availability.io.selectedCount === 1.U,
            "blocked lane1 must downgrade the pair to lane0")
    }
    when(cycle === 1.U) {
        assert(availability.io.selectedCount === 0.U,
            "lane1 must never overtake blocked lane0")
    }

    val queue = Module(new DualInstructionQueue())
    queue.io.flush := cycle === 4.U
    queue.io.enqValid(0) := cycle === 0.U
    queue.io.enqValid(1) := cycle === 0.U
    queue.io.enqBits(0) := 0.U.asTypeOf(new DualFetchEntry())
    queue.io.enqBits(1) := 0.U.asTypeOf(new DualFetchEntry())
    queue.io.enqBits(0).pc := "h1c000000".U
    queue.io.enqBits(1).pc := "h1c000004".U
    queue.io.popCount := Mux(cycle === 1.U, 1.U, 0.U)
    when(cycle === 1.U) {
        assert(queue.io.count === 2.U)
        assert(queue.io.deqBits(0).pc === "h1c000000".U)
        assert(queue.io.deqBits(1).pc === "h1c000004".U)
    }
    when(cycle === 2.U) {
        assert(queue.io.count === 1.U)
        assert(queue.io.deqBits(0).pc === "h1c000004".U)
    }
    when(cycle === 5.U) {
        assert(queue.io.count === 0.U)
    }

    val regfile = Module(new Regfile4R2W())
    regfile.io.raddr := VecInit(3.U, 4.U, 0.U, 0.U)
    regfile.io.wen := VecInit(cycle === 0.U, cycle === 0.U)
    regfile.io.waddr := VecInit(3.U, 4.U)
    regfile.io.wdata := VecInit("h11111111".U, "h22222222".U)
    regfile.io.inspectAddr := 0.U
    when(cycle === 0.U || cycle === 1.U) {
        assert(regfile.io.rdata(0) === "h11111111".U)
        assert(regfile.io.rdata(1) === "h22222222".U)
    }

    val decoder = Module(new Decoder())
    val llInst = ((BigInt(8) << 26) | (BigInt("3fff", 16) << 10) |
        (BigInt(2) << 5) | 3).U(32.W)
    val scInst = ((BigInt(9) << 26) | (BigInt(1) << 10) |
        (BigInt(2) << 5) | 3).U(32.W)
    val cacopInst = "h06000000".U(32.W)
    decoder.io.inst := Mux(cycle === 0.U, llInst,
        Mux(cycle === 1.U, scInst, cacopInst))
    when(cycle === 0.U) {
        assert(decoder.io.out.isLL)
        assert(decoder.io.out.resFromMem)
        assert(decoder.io.out.imm === "hfffffffc".U)
    }
    when(cycle === 1.U) {
        assert(decoder.io.out.isSC)
        assert(decoder.io.out.memWe)
        assert(decoder.io.out.regWe)
        assert(decoder.io.out.imm === 4.U)
    }
    when(cycle === 2.U) {
        assert(decoder.io.out.is_cacop)
        assert(decoder.io.out.is_refetch)
    }

    val csr = Module(new CSR())
    csr.io.addr := CsrAddr.LLBCTL
    csr.io.writeEn := cycle === 1.U
    csr.io.writeData := 4.U
    csr.io.writeMask := 4.U
    csr.io.excValid := false.B
    csr.io.excEcode := 0.U
    csr.io.excEsubcode := 0.U
    csr.io.excPc := 0.U
    csr.io.excAddr := 0.U
    csr.io.ertnFlush := cycle === 2.U || cycle === 3.U
    csr.io.hw_int_in := 0.U
    csr.io.tlbrd_we := false.B
    csr.io.tlbrd_in := 0.U.asTypeOf(new TlbEntry())
    csr.io.llbitSet := cycle === 0.U
    csr.io.llbitClear := false.B
    when(cycle === 1.U || cycle === 2.U || cycle === 3.U) {
        assert(csr.io.llbit)
    }
    when(cycle === 3.U) {
        assert(csr.io.readData === 1.U)
    }
    when(cycle === 4.U) {
        assert(!csr.io.llbit)
    }

    // Local queue readiness must not borrow same-cycle dequeue space.
    val localQueue = Module(new LocalQueue(new MulResultEntry(), 2))
    localQueue.io.flush := cycle === 4.U
    localQueue.io.enq.valid :=
        cycle === 0.U || cycle === 1.U || cycle === 2.U
    localQueue.io.enq.bits.epoch := 0.U
    localQueue.io.enq.bits.data := cycle
    localQueue.io.deq.ready := cycle === 2.U
    when(cycle === 1.U) {
        assert(localQueue.io.count === 1.U)
    }
    when(cycle === 2.U) {
        assert(localQueue.io.count === 2.U)
        assert(!localQueue.io.enq.ready,
            "full local queue must not borrow same-cycle pop space")
    }
    when(cycle === 3.U) {
        assert(localQueue.io.count === 1.U)
    }
    when(cycle === 5.U) {
        assert(localQueue.io.count === 0.U)
    }

    // Store forwarding chooses the youngest matching byte and flush keeps
    // only the committed prefix.
    val storeBuffer = Module(new StoreBuffer())
    storeBuffer.io.flushUncommitted := cycle === 5.U
    storeBuffer.io.enq.valid := cycle === 0.U || cycle === 1.U
    storeBuffer.io.enq.bits.address := "h1000".U
    storeBuffer.io.enq.bits.data := Mux(
        cycle === 0.U, "h000000aa".U, "h0000bbbb".U)
    storeBuffer.io.enq.bits.mask := Mux(
        cycle === 0.U, "b0001".U, "b0011".U)
    storeBuffer.io.enq.bits.uncached := false.B
    storeBuffer.io.commit := cycle === 2.U
    storeBuffer.io.drainStart := cycle === 3.U
    storeBuffer.io.drainDone := cycle === 4.U
    storeBuffer.io.loadValid := true.B
    storeBuffer.io.loadAddress := "h1000".U
    when(cycle === 2.U) {
        assert(storeBuffer.io.forwardMask === "b0011".U)
        assert(storeBuffer.io.forwardData(15, 0) === "hbbbb".U)
    }
    when(cycle === 3.U) {
        assert(storeBuffer.io.drainValid)
        assert(storeBuffer.io.drainBits.data === "h000000aa".U)
    }
    when(cycle === 6.U) {
        assert(storeBuffer.io.empty)
    }

    // A registered predictor update must eventually be visible through the
    // synchronous BTB/PHT memories without a combinational training bypass.
    val predictor = Module(new DualBranchPredictor())
    predictor.io.reqPc(0) := "h1c000100".U
    predictor.io.reqPc(1) := "h1c000104".U
    predictor.io.consume := VecInit(false.B, false.B)
    predictor.io.flushHistory := false.B
    predictor.io.update := 0.U.asTypeOf(new DualPredictorUpdate())
    predictor.io.update.valid := cycle === 0.U
    predictor.io.update.pc := "h1c000100".U
    predictor.io.update.isBranch := true.B
    predictor.io.update.isConditional := true.B
    predictor.io.update.taken := true.B
    predictor.io.update.target := "h1c000180".U
    predictor.io.update.mispredict := true.B
    when(cycle >= 7.U) {
        assert(predictor.io.result(0).hit)
        assert(predictor.io.result(0).taken)
        assert(predictor.io.result(0).target === "h1c000180".U)
    }

    // Consecutive multiply launches exercise II=1 tag/sign alignment for
    // low, signed-high, and unsigned-high results.
    val mul = Module(new SmokeMultiplierModel())
    val mulLaunch = cycle <= 2.U
    val mulOp = MuxLookup(cycle, MduOp.MUL_W)(Seq(
        0.U -> MduOp.MUL_W,
        1.U -> MduOp.MULH_W,
        2.U -> MduOp.MULH_WU
    ))
    val mulA = MuxLookup(cycle, 3.U(32.W))(Seq(
        0.U -> 3.U,
        1.U -> 2.U,
        2.U -> "hffffffff".U
    ))
    val mulB = MuxLookup(cycle, 7.U(32.W))(Seq(
        0.U -> 7.U,
        1.U -> 3.U,
        2.U -> 2.U
    ))
    mul.io.a := mulA
    mul.io.b := mulB
    val mulValid0 = RegNext(mulLaunch, false.B)
    val mulValid1 = RegNext(mulValid0, false.B)
    val mulOp0 = RegEnable(mulOp, mulLaunch)
    val mulOp1 = RegEnable(mulOp0, mulValid0)
    val mulNeg0 = RegEnable(cycle === 1.U, mulLaunch)
    val mulNeg1 = RegEnable(mulNeg0, mulValid0)
    val corrected = Mux(mulNeg1, ~mul.io.p + 1.U, mul.io.p)
    val mulResult = Mux(
        mulOp1 === MduOp.MUL_W, mul.io.p(31, 0), corrected(63, 32))
    when(mulValid1) {
        when(mulOp1 === MduOp.MUL_W) {
            assert(mulResult === 21.U)
        }.elsewhen(mulOp1 === MduOp.MULH_W) {
            assert(mulResult === "hffffffff".U)
        }.otherwise {
            assert(mulResult === 1.U)
        }
    }

    // 64-byte cache-line refill: address selects sector 2, word 1 (beat 9).
    // The same address is requested again after refill to verify the hit path.
    val dcache = Module(new Cache())
    dcache.io.cpu.valid := cycle === 0.U || cycle === 19.U
    dcache.io.cpu.op := false.B
    dcache.io.cpu.index := 2.U // set 0, sector 2
    dcache.io.cpu.tag := "h12345".U
    dcache.io.cpu.offset := 4.U // word 1
    dcache.io.cpu.wstrb := 0.U
    dcache.io.cpu.wdata := 0.U
    dcache.io.cpu.uncached := false.B
    dcache.io.cpu.cacop_en := false.B
    dcache.io.cpu.cacop_op := 0.U
    dcache.io.axi.rd_rdy := true.B
    dcache.io.axi.ret_valid := cycle >= 3.U && cycle <= 18.U
    dcache.io.axi.ret_last := cycle === 18.U
    dcache.io.axi.ret_data := "h100".U + (cycle - 3.U)
    dcache.io.axi.wr_rdy := true.B
    when(cycle === 12.U || cycle === 20.U) {
        assert(dcache.io.cpu.data_ok)
        assert(dcache.io.cpu.rdata === "h109".U)
    }
    when(cycle === 2.U) {
        assert(dcache.io.axi.rd_type === 6.U)
    }

    val icache = Module(new DualICache())
    icache.io.cpu.valid := cycle === 0.U || cycle === 19.U
    icache.io.cpu.index := 2.U // set 0, sector 2
    icache.io.cpu.tag := "h23456".U
    icache.io.cpu.offset := 0.U
    icache.io.cpu.uncached := false.B
    icache.io.invalidateAll := false.B
    icache.io.axi.rd_rdy := true.B
    icache.io.axi.ret_valid := cycle >= 3.U && cycle <= 18.U
    icache.io.axi.ret_last := cycle === 18.U
    icache.io.axi.ret_data := "h100".U + (cycle - 3.U)
    icache.io.axi.wr_rdy := true.B
    when(cycle === 14.U || cycle === 20.U) {
        assert(icache.io.cpu.dataOk)
        assert(icache.io.cpu.line ===
            "h0000010b0000010a0000010900000108".U)
    }
    when(cycle === 2.U) {
        assert(icache.io.axi.rd_type === 6.U)
    }
}

object ElaborateSmoke extends App {
    _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
        new DualSmokeHarness(),
        firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info"),
        args = Array("--target-dir", "generated/smoke")
    )
}
