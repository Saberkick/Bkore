package mycpu

import chisel3._
import chisel3.util._
import chisel3.util.BitPat

/**
  * In-order, lock-step two-wide backend.
  *
  * The packet age invariant is structural: lane 0 is always older than lane 1,
  * and both lanes advance through EX/M1/M2/WB together.  A blocked memory or
  * MDU operation therefore cannot be bypassed by a younger instruction.
  */
class DualBackend extends Module {
    val io = IO(new Bundle {
        val fetchValid = Input(Vec(2, Bool()))
        val fetchBits  = Input(Vec(2, new DualFetchEntry()))
        val popCount   = Output(UInt(2.W))

        val frontendFlush = Output(Bool())
        val frontendTarget = Output(UInt(32.W))
        val flushPredictorHistory = Output(Bool())
        val predictorUpdate = Output(new DualPredictorUpdate())

        val dcache = Flipped(new CacheToCpuIO())
        val icacheInvalidateAll = Output(Bool())

        val mmuConfig = Output(new MmuConfig())
        val tlbVppn = Output(UInt(19.W))
        val tlbVaBit12 = Output(Bool())
        val tlbAsid = Output(UInt(10.W))
        val tlbFound = Input(Bool())
        val tlbIndex = Input(UInt(4.W))
        val tlbPpn = Input(UInt(20.W))
        val tlbPs = Input(UInt(6.W))
        val tlbPlv = Input(UInt(2.W))
        val tlbMat = Input(UInt(2.W))
        val tlbD = Input(Bool())
        val tlbV = Input(Bool())

        val tlbWe = Output(Bool())
        val tlbWIndex = Output(UInt(4.W))
        val tlbWData = Output(new TlbEntry())
        val tlbRIndex = Output(UInt(4.W))
        val tlbRData = Input(new TlbEntry())
        val invtlbValid = Output(Bool())
        val invtlbOp = Output(UInt(5.W))
        val invtlbVppn = Output(UInt(19.W))
        val invtlbAsid = Output(UInt(10.W))

        val timer = Input(UInt(64.W))
        val hwInterrupt = Input(UInt(8.W))

        val debug = Output(Vec(2, new DualCommitDebug()))
        val inspectAddr = Input(UInt(5.W))
        val inspectData = Output(UInt(32.W))
    })

    val emptyPacket = 0.U.asTypeOf(new DualPacket())

    // The three local queues are hard timing boundaries.  Their payload RAMs
    // are not reset and their enqueue readiness is a function of only their
    // own registered count.
    private val pipelineQueueDepth = 2
    val idRrdQueue = Module(new LocalQueue(
        new DualPacket(), pipelineQueueDepth))
    val rrdExQueue = Module(new LocalQueue(
        new DualPacket(), pipelineQueueDepth))
    val exAgQueue = Module(new LocalQueue(
        new PostExPacket(), pipelineQueueDepth))
    val m1M2Queue = Module(new LocalQueue(
        new M2Packet(), pipelineQueueDepth))
    val decodeQueue = Module(new DualDecodeQueue())
    val storeBuffer = Module(new StoreBuffer())

    val exReg = Wire(new DualPacket())
    val m1Reg = Wire(new PostExPacket())
    val m2Reg = Wire(new M2Packet())
    exReg := rrdExQueue.io.deq.bits
    m1Reg := exAgQueue.io.deq.bits
    m2Reg := m1M2Queue.io.deq.bits
    val rrdReg = Wire(new DualPacket())
    rrdReg := idRrdQueue.io.deq.bits

    // WB consumes every cycle.  Keep payload unreset and reset only the two
    // architectural valid bits.
    val wbPayload = Reg(new WbPacket())
    val wbValidBits = RegInit(VecInit(Seq.fill(2)(false.B)))
    val wbReg = Wire(new WbPacket())
    wbReg := wbPayload
    wbReg.valid := wbValidBits

    val exValid = rrdExQueue.io.deq.valid &&
        (exReg.valid(0) || exReg.valid(1))
    val rrdValid = idRrdQueue.io.deq.valid &&
        (rrdReg.valid(0) || rrdReg.valid(1))
    val m1Valid = exAgQueue.io.deq.valid &&
        (m1Reg.valid(0) || m1Reg.valid(1))
    val m2Valid = m1M2Queue.io.deq.valid &&
        (m2Reg.valid(0) || m2Reg.valid(1))
    val wbValid = wbReg.valid(0) || wbReg.valid(1)

    val csr = Module(new CSR())
    val regfile = Module(new Regfile4R2W())
    val issue = Module(new DualIssueUnit())
    val decoders = Seq.fill(2)(Module(new Decoder()))
    val epoch = RegInit(0.U(2.W))

    // ---------------------------------------------------------------------
    // WB: ordered retirement, CSR/TLB state, precise redirect
    // ---------------------------------------------------------------------
    val wbFault0 = wbReg.valid(0) &&
        (wbReg.lane(0).pipe.hasException || wbReg.lane(0).pipe.inst_ertn ||
         wbReg.lane(0).pipe.is_refetch)
    val wbFault1 = wbReg.valid(1) &&
        (wbReg.lane(1).pipe.hasException || wbReg.lane(1).pipe.inst_ertn ||
         wbReg.lane(1).pipe.is_refetch)
    val wbFlush = wbFault0 || wbFault1
    val wbFaultLane = Mux(wbFault0, wbReg.lane(0), wbReg.lane(1))
    val wbFaultPipe = wbFaultLane.pipe

    val wbStoreCommit0 = wbReg.valid(0) &&
        wbReg.lane(0).pipe.memWe &&
        !wbReg.lane(0).pipe.hasException
    val wbStoreCommit1 = wbReg.valid(1) &&
        wbReg.lane(1).pipe.memWe &&
        !wbReg.lane(1).pipe.hasException && !wbFault0
    storeBuffer.io.commit := wbStoreCommit0 || wbStoreCommit1
    storeBuffer.io.flushUncommitted := wbFlush

    csr.io.addr := Mux(wbReg.valid(0), wbReg.lane(0).pipe.csrNum,
        wbReg.lane(1).pipe.csrNum)
    csr.io.writeData := Mux(wbReg.valid(0), wbReg.lane(0).pipe.ex_result,
        wbReg.lane(1).pipe.ex_result)
    csr.io.writeMask := Mux(wbReg.valid(0), wbReg.lane(0).pipe.aux_data,
        wbReg.lane(1).pipe.aux_data)
    val wbCsrWrite = wbReg.valid(0) && wbReg.lane(0).pipe.csrWe &&
        !wbReg.lane(0).pipe.hasException
    csr.io.writeEn := wbCsrWrite

    csr.io.excValid := wbFlush && wbFaultPipe.hasException
    csr.io.excEcode := wbFaultPipe.ecode
    csr.io.excEsubcode := wbFaultPipe.esubcode
    csr.io.excPc := wbFaultPipe.pc
    val wbFetchException = wbFaultPipe.ecode === ExcCode.ADEF ||
        wbFaultPipe.ecode === ExcCode.PIF ||
        wbFaultPipe.inst === "h03400000".U
    csr.io.excAddr := Mux(wbFetchException, wbFaultPipe.pc, wbFaultPipe.ex_result)
    csr.io.ertnFlush := wbFlush && wbFaultPipe.inst_ertn
    csr.io.hw_int_in := io.hwInterrupt

    csr.io.tlbrd_we := wbReg.valid(0) && !wbReg.lane(0).pipe.hasException &&
        wbReg.lane(0).pipe.tlbOp === TlbOp.RD
    csr.io.tlbrd_in := io.tlbRData
    csr.io.llbitSet := wbReg.valid(0) && wbReg.lane(0).pipe.isLL &&
        !wbReg.lane(0).pipe.hasException
    csr.io.llbitClear := wbReg.valid(0) && wbReg.lane(0).pipe.isSC &&
        !wbReg.lane(0).pipe.hasException

    io.mmuConfig := csr.io.mmu_config

    val wbFinalData = Wire(Vec(2, UInt(32.W)))
    val debugPcSeen = RegInit(VecInit(Seq.fill(2)(false.B)))
    for (lane <- 0 until 2) {
        wbFinalData(lane) := Mux(wbReg.lane(lane).pipe.isCsr,
            csr.io.readData, wbReg.lane(lane).pipe.ex_result)
    }

    val suppressLane1 = wbReg.valid(0) && wbReg.lane(0).pipe.hasException
    val wbRfWe = Wire(Vec(2, Bool()))
    for (lane <- 0 until 2) {
        wbRfWe(lane) := wbReg.valid(lane) &&
            wbReg.lane(lane).pipe.regWriteEn &&
            !wbReg.lane(lane).pipe.hasException &&
            (if (lane == 1) !suppressLane1 else true.B)
        regfile.io.wen(lane) := wbRfWe(lane)
        regfile.io.waddr(lane) := wbReg.lane(lane).pipe.destReg
        regfile.io.wdata(lane) := wbFinalData(lane)

        // WB payload registers are intentionally unreset.  Keep the debug PC
        // at zero until the first retirement, then retain the last retired PC
        // between WB pulses.  This preserves chiplab's progress display
        // contract without exposing an empty queue's don't-care payload.
        when(wbReg.valid(lane)) {
            debugPcSeen(lane) := true.B
        }
        io.debug(lane).pc := Mux(
            wbReg.valid(lane) || debugPcSeen(lane),
            wbReg.lane(lane).pipe.pc,
            0.U)
        io.debug(lane).wen := Fill(4, wbRfWe(lane))
        io.debug(lane).wnum := Mux(wbReg.valid(lane),
            wbReg.lane(lane).pipe.destReg, 0.U)
        io.debug(lane).wdata := Mux(wbReg.valid(lane),
            wbFinalData(lane), 0.U)
    }
    regfile.io.inspectAddr := io.inspectAddr
    io.inspectData := regfile.io.inspectData

    val tlbFillIndex = RegInit(0.U(4.W))
    tlbFillIndex := tlbFillIndex + 1.U
    val wbTlbWrite = wbReg.valid(0) && !wbReg.lane(0).pipe.hasException &&
        (wbReg.lane(0).pipe.tlbOp === TlbOp.WR ||
         wbReg.lane(0).pipe.tlbOp === TlbOp.FILL)
    io.tlbWe := wbTlbWrite
    io.tlbWIndex := Mux(wbReg.lane(0).pipe.tlbOp === TlbOp.FILL,
        tlbFillIndex, csr.io.tlbidx_out)
    io.tlbWData := csr.io.tlb_out
    io.tlbRIndex := csr.io.tlbidx_out

    val wbInvtlb = wbReg.valid(0) && !wbReg.lane(0).pipe.hasException &&
        wbReg.lane(0).pipe.tlbOp === TlbOp.INV
    io.invtlbValid := wbInvtlb
    io.invtlbOp := wbReg.lane(0).pipe.invtlb_op
    io.invtlbVppn := wbReg.lane(0).pipe.src2_value(31, 13)
    io.invtlbAsid := wbReg.lane(0).pipe.src1_value(9, 0)

    val wbTarget = Mux(wbFaultPipe.hasException,
        Mux(wbFaultPipe.ecode === ExcCode.TLBR, csr.io.tlbrentryOut, csr.io.eentryOut),
        Mux(wbFaultPipe.inst_ertn, csr.io.eraOut, wbFaultPipe.pc + 4.U))

    // ---------------------------------------------------------------------
    // EX: two ALUs, one shared MDU, branch resolution
    // ---------------------------------------------------------------------
    val exOut = WireDefault(exReg)
    val alus = Seq.fill(2)(Module(new ALU()))

    val exIsMul = exReg.valid(0) && !exReg.lane(0).pipe.hasException &&
        (exReg.lane(0).pipe.mduOp === MduOp.MUL_W ||
         exReg.lane(0).pipe.mduOp === MduOp.MULH_W ||
         exReg.lane(0).pipe.mduOp === MduOp.MULH_WU)
    val exIsDiv = exReg.valid(0) && !exReg.lane(0).pipe.hasException &&
        (exReg.lane(0).pipe.mduOp === MduOp.DIV_W ||
         exReg.lane(0).pipe.mduOp === MduOp.MOD_W ||
         exReg.lane(0).pipe.mduOp === MduOp.DIV_WU ||
         exReg.lane(0).pipe.mduOp === MduOp.MOD_WU)

    val mulIp = Module(new multiplier())
    val mulResultQueue = Module(new LocalQueue(new MulResultEntry(), 4))
    val dcacheResultQueue = Module(new LocalQueue(new DcacheResultEntry(), 4))
    val mulSignedHigh = exReg.lane(0).pipe.mduOp === MduOp.MULH_W
    val mulSrc1 = exReg.lane(0).pipe.src1_value
    val mulSrc2 = exReg.lane(0).pipe.src2_value
    val mulMagnitude1 = Mux(mulSignedHigh && mulSrc1(31), ~mulSrc1 + 1.U, mulSrc1)
    val mulMagnitude2 = Mux(mulSignedHigh && mulSrc2(31), ~mulSrc2 + 1.U, mulSrc2)
    mulIp.io.CLK := clock
    mulIp.io.A := mulMagnitude1
    mulIp.io.B := mulMagnitude2

    val divider = Module(new Divider())
    val divStarted = RegInit(false.B)
    val divFinished = RegInit(false.B)
    val divResult = RegInit(0.U(32.W))
    val divSigned = exReg.lane(0).pipe.mduOp === MduOp.DIV_W ||
        exReg.lane(0).pipe.mduOp === MduOp.MOD_W
    val divA = Mux(divSigned && exReg.lane(0).pipe.src1_value(31),
        ~exReg.lane(0).pipe.src1_value + 1.U, exReg.lane(0).pipe.src1_value)
    val divB = Mux(divSigned && exReg.lane(0).pipe.src2_value(31),
        ~exReg.lane(0).pipe.src2_value + 1.U, exReg.lane(0).pipe.src2_value)
    divider.io.enable := exIsDiv && !divStarted && !divFinished
    divider.io.flush := wbFlush
    divider.io.a := divA
    divider.io.b := divB

    val quotientSign = exReg.lane(0).pipe.src1_value(31) ^
        exReg.lane(0).pipe.src2_value(31)
    val remainderSign = exReg.lane(0).pipe.src1_value(31)
    val signedQ = Mux(divSigned && quotientSign, ~divider.io.q + 1.U, divider.io.q)
    val signedR = Mux(divSigned && remainderSign, ~divider.io.r + 1.U, divider.io.r)
    val liveDivResult = Mux(
        exReg.lane(0).pipe.mduOp === MduOp.DIV_W ||
        exReg.lane(0).pipe.mduOp === MduOp.DIV_WU, signedQ, signedR)
    val currentMduResult = Mux(divider.io.done, liveDivResult, divResult)

    val exReadyGo = !exValid ||
        !exIsDiv ||
        (exIsDiv && (divFinished || divider.io.done))
    val mulLaunch = exIsMul && exReadyGo && exAgQueue.io.enq.ready &&
        !wbFlush

    // Tag/control follows the two IP stages.  Datapath tags are not reset;
    // validity alone defines whether their values are meaningful.
    val mulTagValid0 = RegNext(mulLaunch, false.B)
    val mulTagValid1 = RegNext(mulTagValid0, false.B)
    val mulTagOp0 = RegEnable(exReg.lane(0).pipe.mduOp, mulLaunch)
    val mulTagOp1 = RegEnable(mulTagOp0, mulTagValid0)
    val mulTagNeg0 = RegEnable(
        mulSignedHigh && (mulSrc1(31) ^ mulSrc2(31)), mulLaunch)
    val mulTagNeg1 = RegEnable(mulTagNeg0, mulTagValid0)
    val mulTagEpoch0 = RegEnable(exReg.epoch, mulLaunch)
    val mulTagEpoch1 = RegEnable(mulTagEpoch0, mulTagValid0)
    val signedProduct = Mux(mulTagNeg1, ~mulIp.io.P + 1.U, mulIp.io.P)
    val completedMul = Mux(
        mulTagOp1 === MduOp.MUL_W, mulIp.io.P(31, 0), signedProduct(63, 32))

    mulResultQueue.io.flush := wbFlush
    mulResultQueue.io.enq.valid := mulTagValid1 && !wbFlush
    mulResultQueue.io.enq.bits.epoch := mulTagEpoch1
    mulResultQueue.io.enq.bits.data := completedMul
    assert(!mulTagValid1 || mulResultQueue.io.enq.ready,
        "multiplier result queue overflow")

    for (lane <- 0 until 2) {
        val pipe = exReg.lane(lane).pipe
        val src1 = pipe.src1_value
        val src2 = pipe.src2_value
        val aluSrc1 = Mux(pipe.src1IsPC, pipe.pc, src1)
        val aluSrc2 = Mux(pipe.src2IsImm, pipe.imm,
            Mux(pipe.src2IsFour, 4.U, src2))
        alus(lane).io.aluOp := pipe.aluOp
        alus(lane).io.src1 := aluSrc1
        alus(lane).io.src2 := aluSrc2

        val memVa = src1 + pipe.imm
        val isMemAddress = pipe.resFromMem || pipe.memWe || pipe.is_cacop
        val csrMask = Mux(pipe.src1_addr === 0.U, 0.U,
            Mux(pipe.src1_addr === 1.U, "hffffffff".U, src1))
        val baseResult = Mux(pipe.isSC, csr.io.llbit,
            Mux(isMemAddress, memVa, alus(lane).io.res))
        val result = Mux(pipe.isCpucfg, 0.U,
            Mux(pipe.rdtimel, io.timer(31, 0),
            Mux(pipe.rdtimeh, io.timer(63, 32),
            Mux(pipe.isCsr, src2,
            Mux(pipe.resFromMulDiv && exIsDiv, currentMduResult, baseResult)))))

        val isWord = pipe.lsOp === LsOp.LD_W || pipe.lsOp === LsOp.ST_W
        val isHalf = pipe.lsOp === LsOp.LD_H || pipe.lsOp === LsOp.LD_HU ||
            pipe.lsOp === LsOp.ST_H
        val alignmentException = exReg.valid(lane) &&
            (pipe.resFromMem || pipe.memWe) &&
            ((isWord && memVa(1, 0) =/= 0.U) || (isHalf && memVa(0)))

        exOut.lane(lane).pipe.ex_result := result
        exOut.lane(lane).pipe.aux_data := Mux(pipe.isCsr, csrMask, src2)
        exOut.lane(lane).pipe.hasException := pipe.hasException || alignmentException
        exOut.lane(lane).pipe.ecode := Mux(pipe.hasException, pipe.ecode,
            Mux(alignmentException, ExcCode.ALE, 0.U))
        exOut.lane(lane).pipe.memWe := pipe.memWe && (!pipe.isSC || csr.io.llbit)
    }

    // Branch outcome and predictor update are meaningful only when EX advances.
    val branchTaken = Wire(Vec(2, Bool()))
    val branchTarget = Wire(Vec(2, UInt(32.W)))
    val branchWrong = Wire(Vec(2, Bool()))
    val predictionCheck = Wire(Vec(2, Bool()))
    for (lane <- 0 until 2) {
        val pipe = exReg.lane(lane).pipe
        val eq = pipe.src1_value === pipe.src2_value
        val lt = pipe.src1_value.asSInt < pipe.src2_value.asSInt
        val ltu = pipe.src1_value < pipe.src2_value
        branchTaken(lane) := MuxLookup(pipe.brType, false.B)(Seq(
            BrType.BEQ -> eq, BrType.BNE -> !eq, BrType.BLT -> lt,
            BrType.BGE -> !lt, BrType.BLTU -> ltu, BrType.BGEU -> !ltu,
            BrType.JIRL -> true.B, BrType.B -> true.B, BrType.BL -> true.B
        ))
        val base = Mux(pipe.brType === BrType.JIRL, pipe.src1_value, pipe.pc)
        branchTarget(lane) := base + pipe.imm
        val actualTaken = pipe.brType =/= BrType.NOP && branchTaken(lane)
        branchWrong(lane) := exReg.valid(lane) &&
            ((pipe.predictedTaken =/= actualTaken) ||
             (actualTaken && pipe.predictedTarget =/= branchTarget(lane)))
        predictionCheck(lane) := exReg.valid(lane) &&
            (pipe.brType =/= BrType.NOP || pipe.predictedTaken)
    }

    // ---------------------------------------------------------------------
    // M1: DTLB, permission/alignment result and DCache request
    // ---------------------------------------------------------------------
    // TLB and CACOP operations are serializing, so the issue unit can only
    // place them in lane 0.  Keeping those impossible lane-1 controls in this
    // selector creates a false structural dependency from M1 backpressure to
    // predictor training.  Lane 1 only needs selection for an ordinary LSU
    // operation paired behind a lane-0 integer instruction.
    val m1Selected1 = m1Reg.valid(1) &&
        (m1Reg.lane(1).pipe.resFromMem || m1Reg.lane(1).pipe.memWe)
    val m1Selected = Mux(m1Selected1, m1Reg.lane(1), m1Reg.lane(0))
    val m1Pipe = m1Selected.pipe
    val m1Va = m1Pipe.ex_result
    val m1IsMul = m1Reg.valid(0) &&
        (m1Reg.lane(0).pipe.mduOp === MduOp.MUL_W ||
         m1Reg.lane(0).pipe.mduOp === MduOp.MULH_W ||
         m1Reg.lane(0).pipe.mduOp === MduOp.MULH_WU)
    val mulHeadMatches = mulResultQueue.io.deq.valid &&
        mulResultQueue.io.deq.bits.epoch === m1Reg.epoch
    val mulHeadStale = mulResultQueue.io.deq.valid && !mulHeadMatches
    val m1IsSearch = m1Pipe.tlbOp === TlbOp.SRCH
    val m1Load = m1Pipe.resFromMem && !m1Pipe.hasException
    val m1Store = m1Pipe.memWe && !m1Pipe.hasException
    val m1DcacheCacop =
        m1Pipe.is_cacop && m1Pipe.cacop_op(2, 0) === 1.U
    val m1IcacheCacop =
        m1Pipe.is_cacop && m1Pipe.cacop_op(2, 0) === 0.U
    val m1IsAccess = m1Load || m1Store || m1DcacheCacop
    val m1PrepareNeeded = m1IsAccess || m1IsSearch

    // ---------------------------------------------------------------------
    // Registered M1 translation pipeline
    // ---------------------------------------------------------------------
    // The old single-cycle path selected an M1 lane, selected the TLB query
    // source, searched all 16 entries, performed permission/PA selection and
    // finally searched the StoreBuffer CAM.  At 60 MHz almost 80% of that
    // path was routing.  Split it into three physically local pieces:
    //   M1 packet -> registered query -> registered translation -> LSU.
    // Only memory/CACOP/TLBSRCH packets pay these preparation cycles.
    val m2AllowWire = Wire(Bool())
    val m1FireWire = Wire(Bool())
    val m1QueryValid = RegInit(false.B)
    val m1PreparedValid = RegInit(false.B)

    val m1DirectLive = csr.io.mmu_config.crmd.da === 1.U &&
        csr.io.mmu_config.crmd.pg === 0.U
    val m1Dmw0HitLive = csr.io.mmu_config.crmd.pg === 1.U &&
        !csr.io.mmu_config.crmd.da.asBool &&
        m1Va(31, 29) === csr.io.mmu_config.dmw0.vseg &&
        ((csr.io.mmu_config.crmd.plv === 0.U && csr.io.mmu_config.dmw0.plv0.asBool) ||
         (csr.io.mmu_config.crmd.plv === 3.U && csr.io.mmu_config.dmw0.plv3.asBool))
    val m1Dmw1HitLive = csr.io.mmu_config.crmd.pg === 1.U &&
        !csr.io.mmu_config.crmd.da.asBool &&
        m1Va(31, 29) === csr.io.mmu_config.dmw1.vseg &&
        ((csr.io.mmu_config.crmd.plv === 0.U && csr.io.mmu_config.dmw1.plv0.asBool) ||
         (csr.io.mmu_config.crmd.plv === 3.U && csr.io.mmu_config.dmw1.plv3.asBool))
    val m1DmwHitLive = m1Dmw0HitLive || m1Dmw1HitLive
    val m1DmwPaLive = Mux(m1Dmw0HitLive,
        Cat(csr.io.mmu_config.dmw0.pseg, m1Va(28, 0)),
        Cat(csr.io.mmu_config.dmw1.pseg, m1Va(28, 0)))
    val m1MappedLive = csr.io.mmu_config.crmd.pg === 1.U &&
        !csr.io.mmu_config.crmd.da.asBool && !m1DmwHitLive
    val m1DmwMatLive = Mux(m1Dmw0HitLive,
        csr.io.mmu_config.dmw0.mat, csr.io.mmu_config.dmw1.mat)
    val m1WordLive =
        m1Pipe.lsOp === LsOp.LD_W || m1Pipe.lsOp === LsOp.ST_W
    val m1HalfLive =
        m1Pipe.lsOp === LsOp.LD_H || m1Pipe.lsOp === LsOp.LD_HU ||
        m1Pipe.lsOp === LsOp.ST_H
    val byteMaskLive = "b0001".U(4.W) << m1Va(1, 0)
    val halfMaskLive =
        Mux(m1Va(1), "b1100".U(4.W), "b0011".U(4.W))
    val m1StoreMaskLive = Mux(m1WordLive, "b1111".U,
        Mux(m1HalfLive, halfMaskLive, byteMaskLive))
    val m1StoreDataLive = Mux(m1WordLive, m1Pipe.src2_value,
        Mux(m1HalfLive, Fill(2, m1Pipe.src2_value(15, 0)),
            Fill(4, m1Pipe.src2_value(7, 0))))
    val m1CacopIndexLive =
        m1Pipe.is_cacop && m1Pipe.cacop_op(4, 3) =/= "b10".U

    val m1QueryVa = Reg(UInt(32.W))
    val m1QueryVppn = Reg(UInt(19.W))
    val m1QueryVaBit12 = Reg(Bool())
    val m1QueryAsid = Reg(UInt(10.W))
    val m1QueryDirect = Reg(Bool())
    val m1QueryDmwHit = Reg(Bool())
    val m1QueryDmwPa = Reg(UInt(32.W))
    val m1QueryMapped = Reg(Bool())
    val m1QueryDmwMat = Reg(UInt(2.W))
    val m1QueryDirectMat = Reg(UInt(2.W))
    val m1QueryPlv = Reg(UInt(2.W))
    val m1QueryLoad = Reg(Bool())
    val m1QueryStore = Reg(Bool())
    val m1QueryDcacheCacop = Reg(Bool())
    val m1QueryCacopIndex = Reg(Bool())
    val m1QueryStoreMask = Reg(UInt(4.W))
    val m1QueryStoreData = Reg(UInt(32.W))

    // Keep INVTLB operands off the registered translation search port.
    // Sharing these wires used to put the WB-valid/flush cone in front of all
    // 16 TLB comparators and then back onto every M1 prepared-data register.
    io.tlbVppn := m1QueryVppn
    io.tlbVaBit12 := m1QueryVaBit12
    io.tlbAsid := m1QueryAsid

    val m1QueryTlbPa = Mux(io.tlbPs === 12.U,
        Cat(io.tlbPpn, m1QueryVa(11, 0)),
        Cat(io.tlbPpn(19, 9), m1QueryVa(20, 0)))
    val m1QueryPa = Mux(m1QueryDirect, m1QueryVa,
        Mux(m1QueryDmwHit, m1QueryDmwPa,
        Mux(io.tlbFound && io.tlbV, m1QueryTlbPa, m1QueryVa)))
    val m1QueryIsAccess =
        m1QueryLoad || m1QueryStore || m1QueryDcacheCacop
    val m1Refill =
        m1QueryIsAccess && m1QueryMapped && !io.tlbFound
    val m1Ppi = m1QueryIsAccess && m1QueryMapped &&
        io.tlbFound && io.tlbV && m1QueryPlv > io.tlbPlv
    val m1Pil = (m1QueryLoad || m1QueryDcacheCacop) && m1QueryMapped &&
        io.tlbFound && !io.tlbV
    val m1Pis =
        m1QueryStore && m1QueryMapped && io.tlbFound && !io.tlbV
    val m1Pme = m1QueryStore && m1QueryMapped &&
        io.tlbFound && io.tlbV &&
        !m1Ppi && !io.tlbD
    val m1QueryMmuFault =
        m1Refill || m1Ppi || m1Pil || m1Pis || m1Pme
    val m1QueryFaultCode =
        (Fill(6, m1Refill) & ExcCode.TLBR) |
        (Fill(6, m1Ppi) & ExcCode.PPI) |
        (Fill(6, m1Pil) & ExcCode.PIL) |
        (Fill(6, m1Pis) & ExcCode.PIS) |
        (Fill(6, m1Pme) & ExcCode.PME)
    val m1QueryMat = Mux(m1QueryDirect, m1QueryDirectMat,
        Mux(m1QueryDmwHit, m1QueryDmwMat, io.tlbMat))
    val m1QueryDcacheAddress = Cat(
        Mux(m1QueryCacopIndex, m1QueryVa(31, 12),
            m1QueryPa(31, 12)),
        m1QueryVa(11, 0))

    val m1PreparedPa = Reg(UInt(32.W))
    val m1PreparedMmuFault = Reg(Bool())
    val m1PreparedFaultCode = Reg(UInt(6.W))
    val m1PreparedUncached = Reg(Bool())
    val m1PreparedDcacheAddress = Reg(UInt(32.W))
    val m1PreparedStoreMask = Reg(UInt(4.W))
    val m1PreparedStoreData = Reg(UInt(32.W))
    val m1PreparedSearchResult = Reg(UInt(32.W))
    val m1PreparedSearchAux = Reg(UInt(32.W))

    when(wbFlush || m1FireWire) {
        m1QueryValid := false.B
        m1PreparedValid := false.B
    }.otherwise {
        when(m1Valid && m1PrepareNeeded &&
             !m1QueryValid && !m1PreparedValid) {
            m1QueryValid := true.B
            m1QueryVa := m1Va
            m1QueryVppn := Mux(
                m1IsSearch, csr.io.mmu_config.tlbehi.vppn, m1Va(31, 13))
            m1QueryVaBit12 := m1Va(12)
            m1QueryAsid := csr.io.mmu_config.asid.asid
            m1QueryDirect := m1DirectLive
            m1QueryDmwHit := m1DmwHitLive
            m1QueryDmwPa := m1DmwPaLive
            m1QueryMapped := m1MappedLive
            m1QueryDmwMat := m1DmwMatLive
            m1QueryDirectMat := csr.io.mmu_config.crmd.datm
            m1QueryPlv := csr.io.mmu_config.crmd.plv
            m1QueryLoad := m1Load
            m1QueryStore := m1Store
            m1QueryDcacheCacop := m1DcacheCacop
            m1QueryCacopIndex := m1CacopIndexLive
            m1QueryStoreMask := m1StoreMaskLive
            m1QueryStoreData := m1StoreDataLive
        }
        when(m1QueryValid && !m1PreparedValid && !wbInvtlb) {
            m1QueryValid := false.B
            m1PreparedValid := true.B
            m1PreparedPa := m1QueryPa
            m1PreparedMmuFault := m1QueryMmuFault
            m1PreparedFaultCode := m1QueryFaultCode
            m1PreparedUncached := m1QueryMat === 0.U
            m1PreparedDcacheAddress := m1QueryDcacheAddress
            m1PreparedStoreMask := m1QueryStoreMask
            m1PreparedStoreData := m1QueryStoreData
            m1PreparedSearchResult :=
                Cat(!io.tlbFound, 0.U(27.W), io.tlbIndex)
            m1PreparedSearchAux :=
                Mux(io.tlbFound, "h8000000f".U, "h80000000".U)
        }
    }

    val m1MmuFault = m1PreparedValid && m1PreparedMmuFault
    val m1FaultCode = m1PreparedFaultCode
    val m1Pa = m1PreparedPa
    val m1Uncached = m1PreparedUncached

    storeBuffer.io.loadValid :=
        m1Valid && m1PreparedValid && m1Load && !m1MmuFault
    storeBuffer.io.loadAddress := m1PreparedPa
    // Snapshot store-to-load forwarding when DCache accepts the request.
    // The request-acknowledgement cycle is an intentional timing boundary;
    // CAM results must not be recomputed on the following cycle and then feed
    // the wide M1->M2 payload directly.
    val acceptedForwardMask = Reg(UInt(4.W))
    val acceptedForwardData = Reg(UInt(32.W))
    val captureAcceptedForward = WireDefault(false.B)
    when(captureAcceptedForward) {
        acceptedForwardMask := storeBuffer.io.forwardMask
        acceptedForwardData := storeBuffer.io.forwardData
    }

    val m1Out = WireDefault(m1Reg)
    for (lane <- 0 until 2) {
        val selected = if (lane == 1) m1Selected1 else !m1Selected1
        when(selected && m1Reg.valid(lane)) {
            m1Out.lane(lane).pipe.hasException :=
                m1Reg.lane(lane).pipe.hasException || m1MmuFault
            m1Out.lane(lane).pipe.ecode := Mux(
                m1Reg.lane(lane).pipe.hasException,
                m1Reg.lane(lane).pipe.ecode, m1FaultCode)
            when(m1IsSearch) {
                m1Out.lane(lane).pipe.ex_result :=
                    m1PreparedSearchResult
                m1Out.lane(lane).pipe.aux_data :=
                    m1PreparedSearchAux
            }
            when(lane.U === 0.U && m1IsMul && mulHeadMatches) {
                m1Out.lane(lane).pipe.ex_result :=
                    mulResultQueue.io.deq.bits.data
            }
            m1Out.lane(lane).waitDcache :=
                (m1Load || m1DcacheCacop) && !m1MmuFault
            m1Out.lane(lane).loadForwardMask :=
                acceptedForwardMask
            m1Out.lane(lane).loadForwardData :=
                acceptedForwardData
        }
    }

    val dcacheRequest = m1Valid && m1PreparedValid &&
        (m1Load || m1DcacheCacop) && !m1MmuFault
    // A cache request is acknowledged independently from advancing M1.  This
    // register is the timing boundary between DCache acceptance and the wide
    // M1->M2 queue write enable.
    val m1DcacheIssued = RegInit(false.B)
    val pipelineDcacheGrant =
        dcacheRequest && m2AllowWire && !m1DcacheIssued
    val storeDrainGrant =
        !pipelineDcacheGrant && storeBuffer.io.drainValid
    io.dcache.valid :=
        (pipelineDcacheGrant || storeDrainGrant) && !wbFlush
    io.dcache.op := storeDrainGrant
    val selectedDcacheAddress = Mux(
        storeDrainGrant, storeBuffer.io.drainBits.address,
        m1PreparedDcacheAddress)
    io.dcache.index := selectedDcacheAddress(11, 4)
    io.dcache.tag := selectedDcacheAddress(31, 12)
    io.dcache.offset := selectedDcacheAddress(3, 0)
    io.dcache.wstrb := Mux(
        storeDrainGrant, storeBuffer.io.drainBits.mask, 0.U)
    io.dcache.wdata := Mux(
        storeDrainGrant, storeBuffer.io.drainBits.data, 0.U)
    io.dcache.uncached := Mux(
        storeDrainGrant, storeBuffer.io.drainBits.uncached, m1Uncached)
    io.dcache.cacop_en := !storeDrainGrant && m1DcacheCacop
    io.dcache.cacop_op :=
        Mux(storeDrainGrant, 0.U, m1Pipe.cacop_op(4, 3))

    storeBuffer.io.enq.valid :=
        m1FireWire && m1Store && !m1MmuFault
    storeBuffer.io.enq.bits.address := m1PreparedPa
    storeBuffer.io.enq.bits.data := m1PreparedStoreData
    storeBuffer.io.enq.bits.mask := m1PreparedStoreMask
    storeBuffer.io.enq.bits.uncached := m1Uncached

    // ---------------------------------------------------------------------
    // Registered DCache response boundary and M2 load extraction
    // ---------------------------------------------------------------------
    val dcacheRequestFire = io.dcache.valid && io.dcache.addr_ok
    val pipelineDcacheRequestFire =
        dcacheRequestFire && !storeDrainGrant
    captureAcceptedForward := pipelineDcacheRequestFire
    when(wbFlush) {
        m1DcacheIssued := false.B
    }.elsewhen(m1FireWire) {
        m1DcacheIssued := false.B
    }.elsewhen(pipelineDcacheRequestFire) {
        m1DcacheIssued := true.B
    }
    val dcacheOutstanding = RegInit(false.B)
    val dcacheOwnerStore = Reg(Bool())
    val dcacheResponseEpoch = Reg(UInt(2.W))
    when(dcacheRequestFire) {
        dcacheOwnerStore := storeDrainGrant
        dcacheResponseEpoch := m1Reg.epoch
    }
    when(io.dcache.data_ok && !dcacheRequestFire) {
        dcacheOutstanding := false.B
    }.elsewhen(dcacheRequestFire) {
        dcacheOutstanding := true.B
    }

    // Cache tag/data hit logic is deliberately terminated here before the
    // compacting result queue.  Without this response register, data_ok
    // controlled every payload CE in that queue and exposed the BRAM tag
    // lookup plus hit-selection cone as a single long timing path.
    val dcacheResponseValid = RegInit(false.B)
    val dcacheResponseData = Reg(UInt(32.W))
    val dcacheResponseOwnerStore = Reg(Bool())
    val dcacheResponseQueueEpoch = Reg(UInt(2.W))
    // Payload is don't-care unless dcacheResponseValid is set.  Capture it
    // every cycle so the cache tag-hit/state-machine cone only drives the
    // single valid register instead of the CE pins of 35 payload bits.
    dcacheResponseData := io.dcache.rdata
    dcacheResponseOwnerStore := dcacheOwnerStore
    dcacheResponseQueueEpoch := dcacheResponseEpoch
    when(wbFlush) {
        dcacheResponseValid := false.B
    }.otherwise {
        dcacheResponseValid := io.dcache.data_ok && dcacheOutstanding
    }

    dcacheResultQueue.io.flush := wbFlush
    dcacheResultQueue.io.enq.valid :=
        dcacheResponseValid && !dcacheResponseOwnerStore && !wbFlush
    dcacheResultQueue.io.enq.bits.epoch := dcacheResponseQueueEpoch
    dcacheResultQueue.io.enq.bits.data := dcacheResponseData
    assert(!dcacheResultQueue.io.enq.valid ||
        dcacheResultQueue.io.enq.ready, "DCache result queue overflow")
    storeBuffer.io.drainStart :=
        dcacheRequestFire && storeDrainGrant
    storeBuffer.io.drainDone :=
        io.dcache.data_ok && dcacheOutstanding && dcacheOwnerStore

    val m2Wait = (m2Reg.valid(0) && m2Reg.lane(0).waitDcache) ||
        (m2Reg.valid(1) && m2Reg.lane(1).waitDcache)
    val dcacheHeadMatches = dcacheResultQueue.io.deq.valid &&
        dcacheResultQueue.io.deq.bits.epoch === m2Reg.epoch
    val dcacheHeadStale = m2Valid && m2Wait &&
        dcacheResultQueue.io.deq.valid && !dcacheHeadMatches
    val m2ReadyGo = !m2Valid || !m2Wait || dcacheHeadMatches
    // This signal is deliberately local to the M1->M2 FIFO.  It must never
    // include a combinational dequeue/retire condition from M2 or WB.
    val m2AllowIn = m1M2Queue.io.enq.ready
    m2AllowWire := m2AllowIn

    val m2Out = WireDefault(m2Reg)
    for (lane <- 0 until 2) {
        val pipe = m2Reg.lane(lane).pipe
        val rawDcacheData = dcacheResultQueue.io.deq.bits.data
        val mergedBytes = Wire(Vec(4, UInt(8.W)))
        for (byteIndex <- 0 until 4) {
            mergedBytes(byteIndex) := Mux(
                m2Reg.lane(lane).loadForwardMask(byteIndex),
                m2Reg.lane(lane).loadForwardData(
                    8 * byteIndex + 7, 8 * byteIndex),
                rawDcacheData(8 * byteIndex + 7, 8 * byteIndex))
        }
        val dcacheData = mergedBytes.asUInt
        val offset = pipe.ex_result(1, 0)
        val byte = MuxLookup(offset, 0.U(8.W))(Seq(
            0.U -> dcacheData(7, 0),
            1.U -> dcacheData(15, 8),
            2.U -> dcacheData(23, 16),
            3.U -> dcacheData(31, 24)
        ))
        val half = Mux(offset(1), dcacheData(31, 16),
            dcacheData(15, 0))
        val loadResult = MuxLookup(pipe.lsOp, dcacheData)(Seq(
            LsOp.LD_B -> Cat(Fill(24, byte(7)), byte),
            LsOp.LD_BU -> Cat(0.U(24.W), byte),
            LsOp.LD_H -> Cat(Fill(16, half(15)), half),
            LsOp.LD_HU -> Cat(0.U(16.W), half),
            LsOp.LD_W -> dcacheData
        ))
        when(m2Reg.valid(lane) && pipe.resFromMem && dcacheHeadMatches) {
            m2Out.lane(lane).pipe.ex_result := loadResult
        }
    }

    // ---------------------------------------------------------------------
    // Elastic stage readiness and branch event
    // ---------------------------------------------------------------------
    val m1PrepareReady = !m1PrepareNeeded || m1PreparedValid
    val m1NeedsDcache =
        m1Valid && m1PreparedValid && m1IsAccess && !m1MmuFault
    val m1ResourceReady = !m1NeedsDcache ||
        Mux(m1Store, storeBuffer.io.enq.ready, m1DcacheIssued)
    val m1ReadyGo = !m1Valid ||
        (m1PrepareReady && m1ResourceReady &&
         (!m1IsMul || mulHeadMatches))
    val exFire = exValid && exReadyGo && exAgQueue.io.enq.ready

    val branchLane1 = predictionCheck(1)
    val branchEvent = exFire && (predictionCheck(0) || predictionCheck(1))
    val branchRedirect = branchEvent &&
        Mux(branchLane1, branchWrong(1), branchWrong(0)) && !wbFlush
    val branchPipe = Mux(branchLane1, exReg.lane(1).pipe, exReg.lane(0).pipe)
    val branchActualTaken = Mux(branchLane1,
        exReg.lane(1).pipe.brType =/= BrType.NOP && branchTaken(1),
        exReg.lane(0).pipe.brType =/= BrType.NOP && branchTaken(0))
    val branchResolvedTarget = Mux(branchLane1, branchTarget(1), branchTarget(0))
    val branchNext = Mux(branchActualTaken, branchResolvedTarget,
        branchPipe.pc + 4.U)

    io.predictorUpdate.valid := branchEvent && !wbFlush
    io.predictorUpdate.pc := branchPipe.pc
    io.predictorUpdate.isBranch := branchPipe.brType =/= BrType.NOP
    io.predictorUpdate.isConditional :=
        branchPipe.brType =/= BrType.NOP &&
        branchPipe.brType =/= BrType.JIRL &&
        branchPipe.brType =/= BrType.B &&
        branchPipe.brType =/= BrType.BL
    io.predictorUpdate.taken := branchActualTaken
    io.predictorUpdate.target := branchResolvedTarget
    io.predictorUpdate.mispredict := branchRedirect
    io.predictorUpdate.predictedHit := branchPipe.predictedHit
    io.predictorUpdate.predictedWay := branchPipe.predictedWay
    io.predictorUpdate.predictedCall := branchPipe.predictedCall
    io.predictorUpdate.predictedReturn := branchPipe.predictedReturn
    io.predictorUpdate.historySpeculated := branchPipe.historySpeculated
    io.predictorUpdate.ghrSnapshot := branchPipe.ghrSnapshot
    io.predictorUpdate.rasSpSnapshot := branchPipe.rasSpSnapshot
    io.predictorUpdate.isCall :=
        branchPipe.brType === BrType.BL ||
        (branchPipe.brType === BrType.JIRL &&
         branchPipe.destReg === 1.U)
    io.predictorUpdate.isReturn :=
        branchPipe.brType === BrType.JIRL &&
        branchPipe.src1_addr === 1.U &&
        branchPipe.destReg === 0.U &&
        branchPipe.imm === 0.U

    io.frontendFlush := wbFlush || branchRedirect
    io.frontendTarget := Mux(wbFlush, wbTarget, branchNext)
    io.flushPredictorHistory := wbFlush

    // ---------------------------------------------------------------------
    // ID/Issue: two decoders, 4R2W RF, forwarding and serialization
    // ---------------------------------------------------------------------
    val decodedInput = Wire(Vec(2, new DualLaneData()))

    for (lane <- 0 until 2) {
        decoders(lane).io.inst := io.fetchBits(lane).inst
        val inst = io.fetchBits(lane).inst
        val dec = decoders(lane).io.out
        val op6 = inst(31, 26)
        val isStore = op6 === "b001010".U && inst(24)
        val isSc = op6 === "b001001".U
        val isBranch = op6 === BitPat("b01011?") || op6 === BitPat("b0110??")
        val isCsrWrite = inst(31, 24) === "h04".U && inst(9, 5) =/= 0.U
        val src1 = inst(9, 5)
        val src2 = Mux(isStore || isSc || isBranch || isCsrWrite,
            inst(4, 0), inst(14, 10))

        decodedInput(lane) := 0.U.asTypeOf(new DualLaneData())
        val pipe = decodedInput(lane).pipe
        pipe.pc := io.fetchBits(lane).pc
        pipe.inst := io.fetchBits(lane).inst
        pipe.predictedTaken := io.fetchBits(lane).predictedTaken
        pipe.predictedTarget := io.fetchBits(lane).predictedTarget
        pipe.predictedHit := io.fetchBits(lane).predictedHit
        pipe.predictedWay := io.fetchBits(lane).predictedWay
        pipe.predictedCall := io.fetchBits(lane).predictedCall
        pipe.predictedReturn := io.fetchBits(lane).predictedReturn
        pipe.historySpeculated := io.fetchBits(lane).historySpeculated
        pipe.ghrSnapshot := io.fetchBits(lane).ghrSnapshot
        pipe.rasSpSnapshot := io.fetchBits(lane).rasSpSnapshot
        pipe.aluOp := dec.aluOp
        pipe.mduOp := dec.mduOp
        pipe.brType := dec.brType
        pipe.imm := dec.imm
        pipe.src1IsPC := dec.src1IsPC
        pipe.src2IsImm := dec.src2IsImm
        pipe.src2IsFour := dec.src2IsFour
        pipe.src1_addr := src1
        pipe.src2_addr := src2
        pipe.memWe := dec.memWe
        pipe.lsOp := dec.lsOp
        pipe.resFromMem := dec.resFromMem
        pipe.resFromMulDiv := dec.resFromMulDiv
        pipe.regWriteEn := dec.regWe
        pipe.destReg := dec.destReg
        pipe.isCsr := dec.isCsr
        pipe.csrWe := dec.csrWe
        pipe.csrNum := dec.csrNum
        pipe.inst_ertn := dec.inst_ertn
        pipe.rdtimel := dec.rdtimel
        pipe.rdtimeh := dec.rdtimeh
        pipe.isCpucfg := dec.isCpucfg
        pipe.tlbOp := dec.tlbOp
        pipe.invtlb_op := dec.invtlb_op
        pipe.is_refetch := dec.is_refetch
        pipe.is_cacop := dec.is_cacop
        pipe.cacop_op := dec.cacop_op
        pipe.isLL := dec.isLL
        pipe.isSC := dec.isSC

        // Sample interrupts when the selected packet is handed to ID/RRD.
        // Baking the live interrupt into DecodeQ or the selection register
        // would allow it to become stale while an older instruction waits.
        pipe.hasException := io.fetchBits(lane).hasException ||
            dec.hasException
        pipe.ecode := Mux(
            io.fetchBits(lane).hasException,
            io.fetchBits(lane).ecode,
            dec.ecode)
        pipe.esubcode := io.fetchBits(lane).esubcode
        decodedInput(lane).src1Read := dec.src1_read
        decodedInput(lane).src2Read := dec.src2_read
        decodedInput(lane).serializing :=
            dec.isCsr || dec.tlbOp =/= TlbOp.NOP || dec.is_cacop ||
            dec.inst_ertn || dec.isLL || dec.isSC
    }

    val issueDecoded = Wire(Vec(2, new DualLaneData()))
    issueDecoded := decodeQueue.io.deqBits

    for (lane <- 0 until 2) {
        val pipe = issueDecoded(lane).pipe

        issue.io.in(lane).valid := decodeQueue.io.deqValid(lane)
        issue.io.in(lane).src1Read := issueDecoded(lane).src1Read
        issue.io.in(lane).src1 := pipe.src1_addr
        issue.io.in(lane).src2Read := issueDecoded(lane).src2Read
        issue.io.in(lane).src2 := pipe.src2_addr
        issue.io.in(lane).regWrite := pipe.regWriteEn
        issue.io.in(lane).dest := pipe.destReg
        issue.io.in(lane).isMem :=
            pipe.resFromMem || pipe.memWe || pipe.is_cacop
        issue.io.in(lane).isBranch := pipe.brType =/= BrType.NOP
        issue.io.in(lane).isMdu := pipe.mduOp =/= MduOp.NOP
        issue.io.in(lane).isSerializing := issueDecoded(lane).serializing
        issue.io.in(lane).hasException := pipe.hasException
    }

    case class Producer(
        valid: Bool, write: Bool, dest: UInt, result: UInt, ready: Bool)

    // LocalQueue can contain more than its visible dequeue packet.  A packet
    // in a non-head slot is still architecturally older than the RRD
    // consumer, but its result cannot be bypassed until it reaches slot zero.
    // List hidden slots before the head so the producer list remains ordered
    // from youngest to oldest.
    val hiddenExProducers =
        (pipelineQueueDepth - 1 to 1 by -1).flatMap { slot =>
            (1 to 0 by -1).map { lane =>
                val packet = rrdExQueue.io.slotBits(slot)
                val pipe = packet.lane(lane).pipe
                Producer(rrdExQueue.io.slotValid(slot) && packet.valid(lane),
                    pipe.regWriteEn && !pipe.hasException,
                    pipe.destReg, 0.U(32.W), false.B)
            }
        }

    // Hazard qualification must be based on state already registered at the
    // stage boundary.  Using exOut/m1Out.hasException here feeds the EX
    // alignment and M1 TLB/permission cones back into the RRD queue write
    // enable.  Apart from being a very long control path, that is unnecessary:
    // keeping a newly-faulting producer visible for one extra cycle only
    // stalls younger work that will be flushed at retirement.
    val exProducers = (1 to 0 by -1).map { lane =>
        val registered = exReg.lane(lane).pipe
        val result = exOut.lane(lane).pipe.ex_result
        Producer(rrdExQueue.io.deq.valid && exReg.valid(lane),
            registered.regWriteEn && !registered.hasException,
            registered.destReg, result,
            !registered.resFromMem && !registered.isCsr &&
                (!registered.resFromMulDiv || (exIsDiv && exReadyGo)))
    }
    val hiddenM1Producers =
        (pipelineQueueDepth - 1 to 1 by -1).flatMap { slot =>
            (1 to 0 by -1).map { lane =>
                val packet = exAgQueue.io.slotBits(slot)
                val pipe = packet.lane(lane).pipe
                Producer(exAgQueue.io.slotValid(slot) && packet.valid(lane),
                    pipe.regWriteEn && !pipe.hasException,
                    pipe.destReg, 0.U(32.W), false.B)
            }
        }
    val m1Producers = (1 to 0 by -1).map { lane =>
        val registered = m1Reg.lane(lane).pipe
        // Only multiply can create a forwardable result in M1.  TLB search
        // metadata still travels in m1Out, but must not enter the ordinary
        // register-forwarding mux.
        val result = Mux(registered.resFromMulDiv,
            m1Out.lane(lane).pipe.ex_result, registered.ex_result)
        Producer(exAgQueue.io.deq.valid && m1Reg.valid(lane),
            registered.regWriteEn && !registered.hasException,
            registered.destReg, result,
            !registered.resFromMem && !registered.isCsr &&
                (!registered.resFromMulDiv || mulHeadMatches))
    }
    val hiddenM2Producers =
        (pipelineQueueDepth - 1 to 1 by -1).flatMap { slot =>
            (1 to 0 by -1).map { lane =>
                val packet = m1M2Queue.io.slotBits(slot)
                val pipe = packet.lane(lane).pipe
                Producer(m1M2Queue.io.slotValid(slot) && packet.valid(lane),
                    pipe.regWriteEn && !pipe.hasException,
                    pipe.destReg, 0.U(32.W), false.B)
            }
        }
    val m2Producers = (1 to 0 by -1).map { lane =>
        val registered = m2Reg.lane(lane).pipe
        Producer(m1M2Queue.io.deq.valid && m2Reg.valid(lane),
            registered.regWriteEn && !registered.hasException,
            registered.destReg, m2Out.lane(lane).pipe.ex_result,
            !registered.isCsr &&
                (!registered.resFromMem || dcacheHeadMatches))
    }
    val wbProducers = (1 to 0 by -1).map { lane =>
        Producer(wbReg.valid(lane), wbRfWe(lane),
            wbReg.lane(lane).pipe.destReg, wbFinalData(lane), true.B)
    }
    val forwardingProducers =
        hiddenExProducers ++ exProducers ++
        hiddenM1Producers ++ m1Producers ++
        hiddenM2Producers ++ m2Producers ++ wbProducers

    // The select-stage packet is younger than every valid ID/RRD queue slot.
    // These producers are not included in RRD forwarding because slot zero is
    // the RRD consumer itself and later ID/RRD slots are younger than it.
    val idRrdProducers =
        (pipelineQueueDepth - 1 to 0 by -1).flatMap { slot =>
            (1 to 0 by -1).map { lane =>
                val packet = idRrdQueue.io.slotBits(slot)
                val pipe = packet.lane(lane).pipe
                Producer(idRrdQueue.io.slotValid(slot) && packet.valid(lane),
                    pipe.regWriteEn && !pipe.hasException,
                    pipe.destReg, 0.U(32.W), false.B)
            }
        }
    val scoreboardProducers = idRrdProducers ++ forwardingProducers

    def resolveSource(addr: UInt, read: Bool, rfData: UInt): (UInt, Bool) = {
        // Producer order is youngest to oldest.  All register comparisons are
        // parallel; the age encoder and a single balanced Mux1H replace the
        // old source-code loop that synthesized into a mux cascade.
        val hits = VecInit(forwardingProducers.map { producer =>
            read && addr =/= 0.U && producer.valid &&
                producer.write && producer.dest === addr
        })
        val select = PriorityEncoderOH(hits.asUInt)
        val anyHit = hits.asUInt.orR
        val selectedReady = Mux1H(select,
            VecInit(forwardingProducers.map(_.ready)))
        val selectedValue = Mux1H(select,
            VecInit(forwardingProducers.map(_.result)))
        val blocked = anyHit && !selectedReady
        (Mux(anyHit && selectedReady, selectedValue, rfData), blocked)
    }

    def sourceBlocked(addr: UInt, read: Bool): Bool = {
        val hits = VecInit(scoreboardProducers.map { producer =>
            read && addr =/= 0.U && producer.valid &&
                producer.write && producer.dest === addr
        })
        val select = PriorityEncoderOH(hits.asUInt)
        hits.asUInt.orR && !Mux1H(select,
            VecInit(scoreboardProducers.map(_.ready)))
    }

    // ---------------------------------------------------------------------
    // Issue select / scoreboard stage.
    //
    // Structural pairing is resolved from DecodeQ and the resulting packet
    // is captured before producer readiness is consulted.  The wide DecodeQ
    // payload enables therefore depend only on this one-entry stage and the
    // structural issue unit, never on EX/M1/M2 forwarding or cache/divider
    // completion.  Producer checks terminate at the narrow selectApproved
    // register; RRD still performs the authoritative forwarding selection.
    // ---------------------------------------------------------------------
    val structuralIssueCount = issue.io.issueCount
    val selectedPacketIn = WireDefault(emptyPacket)
    selectedPacketIn.epoch := epoch
    selectedPacketIn.valid(0) := structuralIssueCount =/= 0.U
    selectedPacketIn.valid(1) := structuralIssueCount === 2.U
    for (lane <- 0 until 2) {
        selectedPacketIn.lane(lane) := issueDecoded(lane)
    }

    val selectPayload = Reg(new DualPacket())
    val selectValid = RegInit(false.B)
    val selectApproved = RegInit(false.B)
    val decodeFlush = wbFlush || branchRedirect

    val serialInFlight =
        (rrdExQueue.io.deq.valid && exReg.valid(0) &&
         exReg.lane(0).serializing) ||
        (rrdExQueue.io.deq.valid && exReg.valid(1) &&
         exReg.lane(1).serializing) ||
        (idRrdQueue.io.deq.valid && rrdReg.valid(0) &&
         rrdReg.lane(0).serializing) ||
        (idRrdQueue.io.deq.valid && rrdReg.valid(1) &&
         rrdReg.lane(1).serializing) ||
        (exAgQueue.io.deq.valid && m1Reg.valid(0) &&
         m1Reg.lane(0).serializing) ||
        (exAgQueue.io.deq.valid && m1Reg.valid(1) &&
         m1Reg.lane(1).serializing) ||
        (m1M2Queue.io.deq.valid && m2Reg.valid(0) &&
         m2Reg.lane(0).serializing) ||
        (m1M2Queue.io.deq.valid && m2Reg.valid(1) &&
         m2Reg.lane(1).serializing) ||
        (wbReg.valid(0) && wbReg.lane(0).serializing) ||
        (wbReg.valid(1) && wbReg.lane(1).serializing)

    // An approved non-serial packet can leave while the next DecodeQ packet
    // is captured, preserving one packet/cycle throughput.  Do not replace a
    // departing serializing packet in the same cycle: it is not visible in
    // rrdReg until the following edge.
    val selectAdvance =
        selectValid && selectApproved && idRrdQueue.io.enq.ready
    val selectCanAccept = !selectValid ||
        (selectAdvance && !selectPayload.lane(0).serializing)
    val selectCapture =
        selectCanAccept && structuralIssueCount =/= 0.U
    decodeQueue.io.popCount :=
        Mux(selectCapture, structuralIssueCount, 0.U)

    val scoreboardPacket = WireDefault(selectPayload)
    when(selectCapture) {
        scoreboardPacket := selectedPacketIn
    }

    val scoreboardBlocked = Wire(Vec(4, Bool()))
    for (port <- 0 until 4) {
        val lane = port / 2
        val sourceRead = if (port % 2 == 0) {
            scoreboardPacket.lane(lane).src1Read
        } else {
            scoreboardPacket.lane(lane).src2Read
        }
        val sourceAddr = if (port % 2 == 0) {
            scoreboardPacket.lane(lane).pipe.src1_addr
        } else {
            scoreboardPacket.lane(lane).pipe.src2_addr
        }
        scoreboardBlocked(port) := sourceBlocked(
            sourceAddr,
            scoreboardPacket.valid(lane) && sourceRead)
    }

    val olderWorkPresent =
        rrdValid || exValid || m1Valid || m2Valid || wbValid ||
        !storeBuffer.io.empty
    // If a serializing packet replaces an outgoing ordinary packet, that
    // outgoing packet is also older even though it has not reached rrdReg
    // yet.  Force one scoreboard recheck after the clock edge in that case.
    val serialScoreboardBlocked = serialInFlight ||
        (scoreboardPacket.valid(0) &&
         scoreboardPacket.lane(0).serializing &&
         (olderWorkPresent || selectAdvance))
    val scoreboardReady =
        scoreboardPacket.valid(0) &&
        !scoreboardBlocked.asUInt.orR &&
        !serialScoreboardBlocked

    when(decodeFlush) {
        selectValid := false.B
        selectApproved := false.B
    }.otherwise {
        when(selectCapture) {
            selectPayload := selectedPacketIn
            selectValid := true.B
            selectApproved := scoreboardReady
        }.elsewhen(selectAdvance) {
            selectValid := false.B
            selectApproved := false.B
        }.elsewhen(selectValid && !selectApproved && scoreboardReady) {
            selectApproved := true.B
        }
    }

    // Interrupt state is sampled only as the approved packet crosses the
    // stage boundary.  Lane 1 is discarded on an interrupt; retirement of
    // lane 0 will flush all younger state before it can become visible.
    val issueInterrupt = selectValid && csr.io.hasInt
    val issuePacket = WireDefault(selectPayload)
    issuePacket.valid(0) := selectValid && selectPayload.valid(0)
    issuePacket.valid(1) :=
        selectValid && selectPayload.valid(1) && !issueInterrupt
    issuePacket.lane(0).pipe.hasException :=
        selectPayload.lane(0).pipe.hasException || issueInterrupt
    issuePacket.lane(0).pipe.ecode := Mux(
        issueInterrupt,
        ExcCode.INT,
        selectPayload.lane(0).pipe.ecode)
    val issueFire = selectAdvance && !decodeFlush

    for (lane <- 0 until 2) {
        when(issuePacket.valid(lane)) {
            assert(selectPayload.valid(lane),
                "issue select stage may not manufacture a valid lane")
        }
    }

    val rrdOut = WireDefault(rrdReg)
    val rrdBlocked = Wire(Vec(4, Bool()))
    for (port <- 0 until 4) {
        val lane = port / 2
        val sourceRead = if (port % 2 == 0) {
            rrdReg.lane(lane).src1Read
        } else {
            rrdReg.lane(lane).src2Read
        }
        val sourceAddr = if (port % 2 == 0) {
            rrdReg.lane(lane).pipe.src1_addr
        } else {
            rrdReg.lane(lane).pipe.src2_addr
        }
        regfile.io.raddr(port) := sourceAddr
        val pair = resolveSource(
            sourceAddr,
            rrdValid && rrdReg.valid(lane) && sourceRead,
            regfile.io.rdata(port))
        if (port % 2 == 0) {
            rrdOut.lane(lane).pipe.src1_value := pair._1
        } else {
            rrdOut.lane(lane).pipe.src2_value := pair._1
        }
        rrdBlocked(port) := pair._2
    }
    val rrdFire = rrdValid && !rrdBlocked.asUInt.orR &&
        rrdExQueue.io.enq.ready && !wbFlush && !branchRedirect

    // ---------------------------------------------------------------------
    // Stage register updates.  Retiring flush has global priority; a branch
    // redirect removes only instructions younger than the EX branch.
    // ---------------------------------------------------------------------
    val m1Fire = m1Valid && m1ReadyGo && m1M2Queue.io.enq.ready
    m1FireWire := m1Fire
    val m2Fire = m2Valid && m2ReadyGo
    mulResultQueue.io.deq.ready := mulHeadStale || (m1Fire && m1IsMul)
    dcacheResultQueue.io.deq.ready :=
        dcacheHeadStale || (m2Fire && m2Wait)
    // CACOP is serializing.  Delaying this pulse by one cycle is therefore
    // architecturally invisible and removes the M1 TLB/permission cone from
    // every ICache BRAM write-enable pin.
    io.icacheInvalidateAll := RegNext(
        m1Fire && m1IcacheCacop && !m1MmuFault, false.B)

    // FetchQ now advances only from this buffer's registered capacity.  The
    // instruction payload, decoder, hazard checks and downstream readiness
    // are all on the far side of this timing boundary.
    decodeQueue.io.flush := decodeFlush
    // Do not feed decodeFlush into the wide decoded-payload write enables.
    // Both DecodeQ and FetchQ clear their counts on this same flush, so any
    // payload movement in the flush cycle is unobservable.  Keeping the
    // capacity handshake active also prevents EX branch resolution from
    // travelling back through popCount into every FetchQ payload CE.
    decodeQueue.io.enqValid(0) := io.fetchValid(0)
    decodeQueue.io.enqValid(1) := io.fetchValid(1)
    decodeQueue.io.enqBits := decodedInput
    val decodeAccept0 =
        decodeQueue.io.enqValid(0) && decodeQueue.io.enqReady(0)
    val decodeAccept1 =
        decodeAccept0 && decodeQueue.io.enqValid(1) &&
        decodeQueue.io.enqReady(1)
    io.popCount := Mux(
        decodeAccept1,
        2.U(2.W),
        Mux(decodeAccept0, 1.U(2.W), 0.U(2.W)))

    idRrdQueue.io.flush := wbFlush || branchRedirect
    rrdExQueue.io.flush := wbFlush || branchRedirect
    exAgQueue.io.flush := wbFlush
    m1M2Queue.io.flush := wbFlush

    // Flush has priority over the queue count, so allowing an otherwise valid
    // transfer during a flush cannot make the payload architecturally visible.
    // Keeping decodeFlush away from enq.valid also avoids a new global control
    // path into the wide ID/RRD payload enables.
    idRrdQueue.io.enq.valid := selectAdvance
    idRrdQueue.io.enq.bits := issuePacket
    idRrdQueue.io.deq.ready := rrdFire

    rrdExQueue.io.enq.valid := rrdFire
    rrdExQueue.io.enq.bits := rrdOut
    rrdExQueue.io.deq.ready := exFire

    val exToAg = WireDefault(0.U.asTypeOf(new PostExPacket()))
    exToAg.epoch := exOut.epoch
    exToAg.valid := exOut.valid
    for (lane <- 0 until 2) {
        val source = exOut.lane(lane)
        val target = exToAg.lane(lane)
        target.serializing := source.serializing
        target.waitDcache := source.waitDcache
        target.loadForwardMask := source.loadForwardMask
        target.loadForwardData := source.loadForwardData
        target.pipe.pc := source.pipe.pc
        target.pipe.inst := source.pipe.inst
        target.pipe.mduOp := source.pipe.mduOp
        target.pipe.src1_value := source.pipe.src1_value
        target.pipe.src2_value := source.pipe.src2_value
        target.pipe.resFromMulDiv := source.pipe.resFromMulDiv
        target.pipe.memWe := source.pipe.memWe
        target.pipe.lsOp := source.pipe.lsOp
        target.pipe.resFromMem := source.pipe.resFromMem
        target.pipe.regWriteEn := source.pipe.regWriteEn
        target.pipe.destReg := source.pipe.destReg
        target.pipe.ex_result := source.pipe.ex_result
        target.pipe.aux_data := source.pipe.aux_data
        target.pipe.hasException := source.pipe.hasException
        target.pipe.ecode := source.pipe.ecode
        target.pipe.esubcode := source.pipe.esubcode
        target.pipe.isCsr := source.pipe.isCsr
        target.pipe.csrWe := source.pipe.csrWe
        target.pipe.csrNum := source.pipe.csrNum
        target.pipe.inst_ertn := source.pipe.inst_ertn
        target.pipe.tlbOp := source.pipe.tlbOp
        target.pipe.invtlb_op := source.pipe.invtlb_op
        target.pipe.is_refetch := source.pipe.is_refetch
        target.pipe.is_cacop := source.pipe.is_cacop
        target.pipe.cacop_op := source.pipe.cacop_op
        target.pipe.isLL := source.pipe.isLL
        target.pipe.isSC := source.pipe.isSC
    }

    exAgQueue.io.enq.valid := exFire && !wbFlush
    exAgQueue.io.enq.bits := exToAg
    exAgQueue.io.deq.ready := m1Fire

    val m1ToM2 = WireDefault(0.U.asTypeOf(new M2Packet()))
    m1ToM2.epoch := m1Out.epoch
    m1ToM2.valid := m1Out.valid
    for (lane <- 0 until 2) {
        val source = m1Out.lane(lane)
        val target = m1ToM2.lane(lane)
        target.serializing := source.serializing
        target.waitDcache := source.waitDcache
        target.loadForwardMask := source.loadForwardMask
        target.loadForwardData := source.loadForwardData
        target.pipe.pc := source.pipe.pc
        target.pipe.inst := source.pipe.inst
        target.pipe.src1_value := source.pipe.src1_value
        target.pipe.src2_value := source.pipe.src2_value
        target.pipe.memWe := source.pipe.memWe
        target.pipe.lsOp := source.pipe.lsOp
        target.pipe.resFromMem := source.pipe.resFromMem
        target.pipe.regWriteEn := source.pipe.regWriteEn
        target.pipe.destReg := source.pipe.destReg
        target.pipe.ex_result := source.pipe.ex_result
        target.pipe.aux_data := source.pipe.aux_data
        target.pipe.hasException := source.pipe.hasException
        target.pipe.ecode := source.pipe.ecode
        target.pipe.esubcode := source.pipe.esubcode
        target.pipe.isCsr := source.pipe.isCsr
        target.pipe.csrWe := source.pipe.csrWe
        target.pipe.csrNum := source.pipe.csrNum
        target.pipe.inst_ertn := source.pipe.inst_ertn
        target.pipe.tlbOp := source.pipe.tlbOp
        target.pipe.invtlb_op := source.pipe.invtlb_op
        target.pipe.is_refetch := source.pipe.is_refetch
        target.pipe.isLL := source.pipe.isLL
        target.pipe.isSC := source.pipe.isSC
    }

    m1M2Queue.io.enq.valid := m1Fire && !wbFlush
    m1M2Queue.io.enq.bits := m1ToM2
    m1M2Queue.io.deq.ready := m2Fire

    val m2ToWb = WireDefault(0.U.asTypeOf(new WbPacket()))
    m2ToWb.epoch := m2Out.epoch
    m2ToWb.valid := m2Out.valid
    for (lane <- 0 until 2) {
        val source = m2Out.lane(lane)
        val target = m2ToWb.lane(lane)
        target.serializing := source.serializing
        target.pipe.pc := source.pipe.pc
        target.pipe.inst := source.pipe.inst
        target.pipe.src1_value := source.pipe.src1_value
        target.pipe.src2_value := source.pipe.src2_value
        target.pipe.memWe := source.pipe.memWe
        target.pipe.regWriteEn := source.pipe.regWriteEn
        target.pipe.destReg := source.pipe.destReg
        target.pipe.ex_result := source.pipe.ex_result
        target.pipe.aux_data := source.pipe.aux_data
        target.pipe.hasException := source.pipe.hasException
        target.pipe.ecode := source.pipe.ecode
        target.pipe.esubcode := source.pipe.esubcode
        target.pipe.isCsr := source.pipe.isCsr
        target.pipe.csrWe := source.pipe.csrWe
        target.pipe.csrNum := source.pipe.csrNum
        target.pipe.inst_ertn := source.pipe.inst_ertn
        target.pipe.tlbOp := source.pipe.tlbOp
        target.pipe.invtlb_op := source.pipe.invtlb_op
        target.pipe.is_refetch := source.pipe.is_refetch
        target.pipe.isLL := source.pipe.isLL
        target.pipe.isSC := source.pipe.isSC
    }

    when(wbFlush) {
        wbValidBits := VecInit(Seq.fill(2)(false.B))
        divStarted := false.B
        divFinished := false.B
    }.otherwise {
        // WB always retires its current packet.  Payload is written only when
        // M2 produces a packet; otherwise only the narrow valid state clears.
        when(m2Fire) {
            wbPayload := m2ToWb
            wbValidBits := m2ToWb.valid
        }.otherwise {
            wbValidBits := VecInit(Seq.fill(2)(false.B))
        }

        when(divider.io.enable && divider.io.ready) {
            divStarted := true.B
        }
        when(divider.io.done) {
            divResult := liveDivResult
            divFinished := true.B
        }
        when(exFire) {
            divStarted := false.B
            divFinished := false.B
        }
    }

    when(wbFlush || branchRedirect) {
        epoch := epoch + 1.U
    }

    // Lockstep invariants are part of the architectural contract: lane 1
    // cannot exist without its older lane 0, and the shared LSU can see at
    // most one operation in any packet.
    def assertPacket(validBits: Vec[Bool], lane1Serial: Bool,
                     mem0: Bool, mem1: Bool): Unit = {
        assert(!validBits(1) || validBits(0),
            "dual packet lane1 must never overtake lane0")
        assert(!(validBits(1) && lane1Serial),
            "serializing operations must remain in lane0")
        assert(!(mem0 && mem1), "dual packet contains two LSU operations")
    }
    assertPacket(exReg.valid, exReg.lane(1).serializing,
        exReg.valid(0) &&
            (exReg.lane(0).pipe.resFromMem || exReg.lane(0).pipe.memWe),
        exReg.valid(1) &&
            (exReg.lane(1).pipe.resFromMem || exReg.lane(1).pipe.memWe))
    assertPacket(m1Reg.valid, m1Reg.lane(1).serializing,
        m1Reg.valid(0) &&
            (m1Reg.lane(0).pipe.resFromMem || m1Reg.lane(0).pipe.memWe),
        m1Reg.valid(1) &&
            (m1Reg.lane(1).pipe.resFromMem || m1Reg.lane(1).pipe.memWe))
    assertPacket(m2Reg.valid, m2Reg.lane(1).serializing,
        m2Reg.valid(0) &&
            (m2Reg.lane(0).pipe.resFromMem || m2Reg.lane(0).pipe.memWe),
        m2Reg.valid(1) &&
            (m2Reg.lane(1).pipe.resFromMem || m2Reg.lane(1).pipe.memWe))
    when(serialInFlight) {
        assert(!issueFire,
            "no younger instruction may issue behind a serializing operation")
    }
}
