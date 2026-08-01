package mycpu

import chisel3._

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

    val cycle = RegInit(0.U(4.W))
    when(cycle =/= 15.U) {
        cycle := cycle + 1.U
    }
    io.done := cycle >= 15.U

    val issue = Module(new DualIssueUnit())
    for (lane <- 0 until 2) {
        issue.io.in(lane) := 0.U.asTypeOf(new DualIssueInfo())
        issue.io.in(lane).valid := cycle <= 4.U
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
    when(cycle === 3.U) {
        issue.io.in(0).isMem := true.B
        issue.io.in(1).isMem := true.B
        issue.io.in(0).cacheable := true.B
        issue.io.in(1).cacheable := true.B
        issue.io.in(0).memBank := false.B
        issue.io.in(1).memBank := true.B
        assert(issue.io.issueCount === 2.U)
    }
    when(cycle === 4.U) {
        issue.io.in(0).isMdu := true.B
        issue.io.in(0).isMul := true.B
        assert(issue.io.issueCount === 2.U)
    }

    val predictor = Module(new DualBranchPredictor(clearSets = 4))
    predictor.io.reqValid := cycle === 6.U || cycle === 10.U || cycle === 14.U
    val predictorPc = Mux(cycle === 10.U, "h1c000008".U,
        Mux(cycle === 14.U, "h1c002000".U, "h1c000000".U))
    predictor.io.reqPc := VecInit(predictorPc, predictorPc + 4.U)
    predictor.io.consume := cycle === 11.U
    predictor.io.consumeSlot := 0.U
    predictor.io.flush := false.B
    predictor.io.rasCommit := 0.U.asTypeOf(new DualRasCommit())
    predictor.io.update := 0.U.asTypeOf(new DualPredictorUpdate())
    when(cycle === 4.U) {
        predictor.io.update.valid := true.B
        predictor.io.update.pc := "h1c000000".U
        predictor.io.update.isBranch := true.B
        predictor.io.update.target := "h1c001000".U
    }
    when(cycle === 8.U) {
        predictor.io.update.valid := true.B
        predictor.io.update.pc := "h1c000008".U
        predictor.io.update.isBranch := true.B
        predictor.io.update.isCall := true.B
        predictor.io.update.target := "h1c002000".U
    }
    when(cycle === 12.U) {
        predictor.io.update.valid := true.B
        predictor.io.update.pc := "h1c002000".U
        predictor.io.update.isBranch := true.B
        predictor.io.update.isReturn := true.B
        predictor.io.update.target := 0.U
    }
    when(cycle === 7.U) {
        assert(predictor.io.resultValid)
        assert(predictor.io.result(0).hit)
        assert(predictor.io.result(0).taken)
        assert(predictor.io.result(0).target === "h1c001000".U)
    }
    when(cycle === 11.U) {
        assert(predictor.io.result(0).isCall)
        assert(predictor.io.result(0).target === "h1c002000".U)
    }
    when(cycle === 15.U) {
        assert(predictor.io.result(0).isReturn)
        assert(predictor.io.result(0).target === "h1c00000c".U)
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
}

object ElaborateSmoke extends App {
    circt.stage.ChiselStage.emitSystemVerilogFile(
        new DualSmokeHarness(),
        firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info"),
        args = Array("--target-dir", "generated/smoke")
    )
}
