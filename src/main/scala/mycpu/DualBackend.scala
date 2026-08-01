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
        val rasCommit = Output(new DualRasCommit())

        val dcache = Flipped(Vec(2, new DCacheLaneIO()))
        val icacheInvalidateAll = Output(Bool())

        val mmuConfig = Output(new MmuConfig())
        val tlbVppn = Output(Vec(2, UInt(19.W)))
        val tlbVaBit12 = Output(Vec(2, Bool()))
        val tlbAsid = Output(Vec(2, UInt(10.W)))
        val tlbFound = Input(Vec(2, Bool()))
        val tlbIndex = Input(Vec(2, UInt(4.W)))
        val tlbPpn = Input(Vec(2, UInt(20.W)))
        val tlbPs = Input(Vec(2, UInt(6.W)))
        val tlbPlv = Input(Vec(2, UInt(2.W)))
        val tlbMat = Input(Vec(2, UInt(2.W)))
        val tlbD = Input(Vec(2, Bool()))
        val tlbV = Input(Vec(2, Bool()))

        val tlbWe = Output(Bool())
        val tlbWIndex = Output(UInt(4.W))
        val tlbWData = Output(new TlbEntry())
        val tlbRIndex = Output(UInt(4.W))
        val tlbRData = Input(new TlbEntry())
        val invtlbValid = Output(Bool())
        val invtlbOp = Output(UInt(5.W))

        val timer = Input(UInt(64.W))
        val hwInterrupt = Input(UInt(8.W))

        val debug = Output(Vec(2, new DualCommitDebug()))
        val inspectAddr = Input(UInt(5.W))
        val inspectData = Output(UInt(32.W))

        val issueBlockMask = Output(UInt(DualIssueBlockReason.Width.W))
    })

    val emptyPacket = 0.U.asTypeOf(new DualPacket())
    val exReg = RegInit(emptyPacket)
    val m1Reg = RegInit(emptyPacket)
    val m2Reg = RegInit(emptyPacket)
    val wbReg = RegInit(emptyPacket)

    // Two packet issue buffer.  Its registered occupancy is the credit seen
    // by the frontend queue, so M1/DCache/TLB backpressure cannot propagate
    // combinationally through EX and the issue selector into queue.popCount.
    // Entries which have not reached EX are scoreboard producers (not ready),
    // preventing a younger dependent instruction from capturing stale RF data.
    val issueQueue = RegInit(VecInit(Seq.fill(2)(
        0.U.asTypeOf(new DualPacket()))))
    val issueQueueCount = RegInit(0.U(2.W))

    def packetValid(packet: DualPacket): Bool = packet.valid(0) || packet.valid(1)
    val exValid = packetValid(exReg)
    val m1Valid = packetValid(m1Reg)
    val m2Valid = packetValid(m2Reg)
    val wbValid = packetValid(wbReg)

    val csr = Module(new CSR())
    val regfile = Module(new Regfile4R2W())
    val issue = Module(new DualIssueUnit())
    val decoders = Seq.fill(2)(Module(new Decoder()))

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

        io.debug(lane).pc := wbReg.lane(lane).pipe.pc
        io.debug(lane).wen := Fill(4, wbRfWe(lane))
        io.debug(lane).wnum := wbReg.lane(lane).pipe.destReg
        io.debug(lane).wdata := wbFinalData(lane)
    }
    regfile.io.inspectAddr := io.inspectAddr
    io.inspectData := regfile.io.inspectData

    val wbRasLane1 = wbReg.valid(1) && !suppressLane1 &&
        !wbReg.lane(1).pipe.hasException &&
        wbReg.lane(1).pipe.brType =/= BrType.NOP
    val wbRasPipe = Mux(wbRasLane1, wbReg.lane(1).pipe, wbReg.lane(0).pipe)
    val wbRasValid = (wbRasLane1 || (wbReg.valid(0) &&
        !wbReg.lane(0).pipe.hasException &&
        wbReg.lane(0).pipe.brType =/= BrType.NOP)) && !wbFlush
    io.rasCommit.valid := wbRasValid && (
        wbRasPipe.brType === BrType.BL ||
        (wbRasPipe.brType === BrType.JIRL && wbRasPipe.destReg === 1.U) ||
        wbRasPipe.inst === "h4c000020".U)
    io.rasCommit.isCall := wbRasPipe.brType === BrType.BL ||
        (wbRasPipe.brType === BrType.JIRL && wbRasPipe.destReg === 1.U)
    io.rasCommit.isReturn := wbRasPipe.inst === "h4c000020".U
    io.rasCommit.returnAddress := wbRasPipe.pc + 4.U

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

    val mulSigned = exReg.lane(0).pipe.mduOp === MduOp.MULH_W
    // A single sign-extended 33x33 multiply covers both signed and unsigned
    // products.  The low 64 bits are identical to the corresponding 32x32
    // result, avoiding two parallel multipliers and a result-wide mux.
    val mulA = Cat(mulSigned && exReg.lane(0).pipe.src1_value(31),
        exReg.lane(0).pipe.src1_value).asSInt
    val mulB = Cat(mulSigned && exReg.lane(0).pipe.src2_value(31),
        exReg.lane(0).pipe.src2_value).asSInt
    val mulProduct = (mulA * mulB).asUInt
    val liveMulResult = Mux(exReg.lane(0).pipe.mduOp === MduOp.MUL_W,
        mulProduct(31, 0), mulProduct(63, 32))

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
    val currentMduResult = Mux(exIsMul, liveMulResult,
        Mux(divider.io.done, liveDivResult, divResult))

    val exReadyGo = !exValid ||
        (!exIsMul && !exIsDiv) ||
        exIsMul ||
        (exIsDiv && (divFinished || divider.io.done))

    val exForwardResult = Wire(Vec(2, UInt(32.W)))
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
        val nonMduResult = Mux(pipe.isCpucfg, 0.U,
            Mux(pipe.rdtimel, io.timer(31, 0),
            Mux(pipe.rdtimeh, io.timer(63, 32),
            Mux(pipe.isCsr, src2, baseResult))))
        val result = Mux(pipe.resFromMulDiv, currentMduResult, nonMduResult)
        // Never expose the unified MDU result mux to ID.  MUL is registered at
        // the M1 boundary and DIV is blocking; both are marked not-ready by
        // the EX producer.  Keeping this bus MDU-free prevents a DSP cascade
        // from leaking through source forwarding into issue/popCount.
        exForwardResult(lane) := nonMduResult

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
            (pipe.brType =/= BrType.NOP || pipe.predictedHit)
    }

    // ---------------------------------------------------------------------
    // M1: DTLB, permission/alignment result and DCache request
    // ---------------------------------------------------------------------
    val wbInvVppn = wbReg.lane(0).pipe.src2_value(31, 13)
    val wbInvAsid = wbReg.lane(0).pipe.src1_value(9, 0)
    val m1Direct = csr.io.mmu_config.crmd.da === 1.U &&
        csr.io.mmu_config.crmd.pg === 0.U
    val m1Va = Wire(Vec(2, UInt(32.W)))
    val m1Pa = Wire(Vec(2, UInt(32.W)))
    val m1Load = Wire(Vec(2, Bool()))
    val m1Store = Wire(Vec(2, Bool()))
    val m1DcacheCacop = Wire(Vec(2, Bool()))
    val m1IcacheCacop = Wire(Vec(2, Bool()))
    val m1IsAccess = Wire(Vec(2, Bool()))
    val m1IsSearch = Wire(Vec(2, Bool()))
    val m1MmuFault = Wire(Vec(2, Bool()))
    val m1FaultCode = Wire(Vec(2, UInt(6.W)))
    val m1Uncached = Wire(Vec(2, Bool()))
    val m1Out = WireDefault(m1Reg)

    for (lane <- 0 until 2) {
        val pipe = m1Reg.lane(lane).pipe
        val valid = m1Reg.valid(lane)
        val va = pipe.ex_result
        m1Va(lane) := va
        val isInv = pipe.tlbOp === TlbOp.INV
        m1IsSearch(lane) := pipe.tlbOp === TlbOp.SRCH

        if (lane == 0) {
            io.tlbVppn(lane) := Mux(wbInvtlb, wbInvVppn,
                Mux(isInv, pipe.src2_value(31, 13),
                Mux(m1IsSearch(lane), csr.io.mmu_config.tlbehi.vppn,
                    va(31, 13))))
            io.tlbAsid(lane) := Mux(wbInvtlb, wbInvAsid,
                Mux(isInv, pipe.src1_value(9, 0), csr.io.mmu_config.asid.asid))
        } else {
            io.tlbVppn(lane) := va(31, 13)
            io.tlbAsid(lane) := csr.io.mmu_config.asid.asid
        }
        io.tlbVaBit12(lane) := va(12)

        val dmw0Hit = csr.io.mmu_config.crmd.pg === 1.U &&
            !csr.io.mmu_config.crmd.da.asBool &&
            va(31, 29) === csr.io.mmu_config.dmw0.vseg &&
            ((csr.io.mmu_config.crmd.plv === 0.U && csr.io.mmu_config.dmw0.plv0.asBool) ||
             (csr.io.mmu_config.crmd.plv === 3.U && csr.io.mmu_config.dmw0.plv3.asBool))
        val dmw1Hit = csr.io.mmu_config.crmd.pg === 1.U &&
            !csr.io.mmu_config.crmd.da.asBool &&
            va(31, 29) === csr.io.mmu_config.dmw1.vseg &&
            ((csr.io.mmu_config.crmd.plv === 0.U && csr.io.mmu_config.dmw1.plv0.asBool) ||
             (csr.io.mmu_config.crmd.plv === 3.U && csr.io.mmu_config.dmw1.plv3.asBool))
        val dmwHit = dmw0Hit || dmw1Hit
        val dmwPa = Mux(dmw0Hit,
            Cat(csr.io.mmu_config.dmw0.pseg, va(28, 0)),
            Cat(csr.io.mmu_config.dmw1.pseg, va(28, 0)))
        val tlbPa = Mux(io.tlbPs(lane) === 12.U,
            Cat(io.tlbPpn(lane), va(11, 0)),
            Cat(io.tlbPpn(lane)(19, 9), va(20, 0)))
        m1Pa(lane) := Mux(m1Direct, va,
            Mux(dmwHit, dmwPa,
            Mux(io.tlbFound(lane) && io.tlbV(lane), tlbPa, va)))
        val mapped = csr.io.mmu_config.crmd.pg === 1.U &&
            !csr.io.mmu_config.crmd.da.asBool && !dmwHit

        m1Load(lane) := valid && pipe.resFromMem && !pipe.hasException
        m1Store(lane) := valid && pipe.memWe && !pipe.hasException
        m1DcacheCacop(lane) := valid && pipe.is_cacop &&
            pipe.cacop_op(2, 0) === 1.U
        m1IcacheCacop(lane) := valid && pipe.is_cacop &&
            pipe.cacop_op(2, 0) === 0.U
        m1IsAccess(lane) := m1Load(lane) || m1Store(lane) || m1DcacheCacop(lane)
        val refill = m1IsAccess(lane) && mapped && !io.tlbFound(lane)
        val ppi = m1IsAccess(lane) && mapped && io.tlbFound(lane) &&
            io.tlbV(lane) && csr.io.mmu_config.crmd.plv > io.tlbPlv(lane)
        val pil = (m1Load(lane) || m1DcacheCacop(lane)) && mapped &&
            io.tlbFound(lane) && !io.tlbV(lane)
        val pis = m1Store(lane) && mapped && io.tlbFound(lane) && !io.tlbV(lane)
        val pme = m1Store(lane) && mapped && io.tlbFound(lane) &&
            io.tlbV(lane) && !ppi && !io.tlbD(lane)
        m1MmuFault(lane) := refill || ppi || pil || pis || pme
        m1FaultCode(lane) :=
            (Fill(6, refill) & ExcCode.TLBR) |
            (Fill(6, ppi) & ExcCode.PPI) |
            (Fill(6, pil) & ExcCode.PIL) |
            (Fill(6, pis) & ExcCode.PIS) |
            (Fill(6, pme) & ExcCode.PME)

        val dmwMat = Mux(dmw0Hit, csr.io.mmu_config.dmw0.mat,
            csr.io.mmu_config.dmw1.mat)
        val mat = Mux(m1Direct, csr.io.mmu_config.crmd.datm,
            Mux(dmwHit, dmwMat, io.tlbMat(lane)))
        m1Uncached(lane) := mat === 0.U

        when(valid) {
            m1Out.lane(lane).pipe.hasException :=
                pipe.hasException || m1MmuFault(lane)
            m1Out.lane(lane).pipe.ecode := Mux(
                pipe.hasException, pipe.ecode, m1FaultCode(lane))
            when(m1IsSearch(lane)) {
                m1Out.lane(lane).pipe.ex_result :=
                    Cat(!io.tlbFound(lane), 0.U(27.W), io.tlbIndex(lane))
                m1Out.lane(lane).pipe.aux_data :=
                    Mux(io.tlbFound(lane), "h8000000f".U, "h80000000".U)
            }
        }
    }

    val lane0FaultBlocksLane1 = m1Reg.valid(0) &&
        (m1Reg.lane(0).pipe.hasException || m1MmuFault(0))
    val dcacheRequest = Wire(Vec(2, Bool()))
    dcacheRequest(0) := m1IsAccess(0) && !m1MmuFault(0)
    dcacheRequest(1) := m1IsAccess(1) && !m1MmuFault(1) && !lane0FaultBlocksLane1

    // m2AllowIn is declared below; this wire breaks declaration order.
    val m2AllowWire = Wire(Bool())
    val dualDcacheRequest = dcacheRequest.asUInt.andR
    val bothBanksReady = io.dcache(0).addr_ok && io.dcache(1).addr_ok
    for (lane <- 0 until 2) {
        val pipe = m1Reg.lane(lane).pipe
        val cacopIndex = pipe.is_cacop && pipe.cacop_op(4, 3) =/= "b10".U
        val address = Cat(Mux(cacopIndex, m1Va(lane)(31, 12),
            m1Pa(lane)(31, 12)), m1Va(lane)(11, 0))
        val word = pipe.lsOp === LsOp.LD_W || pipe.lsOp === LsOp.ST_W
        val half = pipe.lsOp === LsOp.LD_H || pipe.lsOp === LsOp.LD_HU ||
            pipe.lsOp === LsOp.ST_H
        val byteMask = "b0001".U(4.W) << m1Va(lane)(1, 0)
        val halfMask = Mux(m1Va(lane)(1), "b1100".U(4.W), "b0011".U(4.W))

        // For a pair, assert both valids only after both banks advertise
        // readiness.  This makes request acceptance atomic and avoids losing a
        // fast-bank response while the other bank is still blocked.
        io.dcache(lane).request := dcacheRequest(lane)
        io.dcache(lane).valid := dcacheRequest(lane) && m2AllowWire &&
            (!dualDcacheRequest || bothBanksReady)
        io.dcache(lane).op := m1Store(lane)
        io.dcache(lane).addr := address
        io.dcache(lane).wstrb := Mux(m1Store(lane),
            Mux(word, "b1111".U, Mux(half, halfMask, byteMask)), 0.U)
        io.dcache(lane).wdata := Mux(word, pipe.src2_value,
            Mux(half, Fill(2, pipe.src2_value(15, 0)),
                Fill(4, pipe.src2_value(7, 0))))
        io.dcache(lane).uncached := m1Uncached(lane)
        io.dcache(lane).cacop_en := m1DcacheCacop(lane)
        io.dcache(lane).cacop_op := pipe.cacop_op(4, 3)
        io.dcache(lane).responseBank := Mux(
            m2Reg.valid(lane) && m2Reg.lane(lane).waitDcache,
            m2Reg.lane(lane).dcacheBank, address(4))

        m1Out.lane(lane).waitDcache := dcacheRequest(lane)
        m1Out.lane(lane).dcacheDone := false.B
        m1Out.lane(lane).dcacheBank := address(4)
    }

    val dcacheRequestsAccepted = (0 until 2).map { lane =>
        !dcacheRequest(lane) ||
            (io.dcache(lane).valid && io.dcache(lane).addr_ok)
    }.reduce(_ && _)

    // ---------------------------------------------------------------------
    // M2: blocking DCache completion and load extraction
    // ---------------------------------------------------------------------
    val m2Done = Wire(Vec(2, Bool()))
    for (lane <- 0 until 2) {
        m2Done(lane) := !m2Reg.valid(lane) || !m2Reg.lane(lane).waitDcache ||
            m2Reg.lane(lane).dcacheDone || io.dcache(lane).data_ok
    }
    val m2ReadyGo = !m2Valid || m2Done.asUInt.andR
    val m2AllowIn = !m2Valid || m2ReadyGo
    m2AllowWire := m2AllowIn

    val m2Out = WireDefault(m2Reg)
    for (lane <- 0 until 2) {
        val pipe = m2Reg.lane(lane).pipe
        val rdata = io.dcache(lane).rdata
        val offset = pipe.ex_result(1, 0)
        val byte = MuxLookup(offset, 0.U(8.W))(Seq(
            0.U -> rdata(7, 0),
            1.U -> rdata(15, 8),
            2.U -> rdata(23, 16),
            3.U -> rdata(31, 24)
        ))
        val half = Mux(offset(1), rdata(31, 16), rdata(15, 0))
        val loadResult = MuxLookup(pipe.lsOp, rdata)(Seq(
            LsOp.LD_B -> Cat(Fill(24, byte(7)), byte),
            LsOp.LD_BU -> Cat(0.U(24.W), byte),
            LsOp.LD_H -> Cat(Fill(16, half(15)), half),
            LsOp.LD_HU -> Cat(0.U(16.W), half),
            LsOp.LD_W -> rdata
        ))
        when(m2Reg.valid(lane) && m2Reg.lane(lane).waitDcache &&
                io.dcache(lane).data_ok) {
            m2Out.lane(lane).dcacheDone := true.B
            when(pipe.resFromMem) {
                m2Out.lane(lane).pipe.ex_result := loadResult
            }
        }
    }

    // ---------------------------------------------------------------------
    // Elastic stage readiness and branch event
    // ---------------------------------------------------------------------
    val m1ReadyGo = !m1Valid || dcacheRequestsAccepted
    val m1AllowIn = !m1Valid || (m1ReadyGo && m2AllowIn)
    val exAllowIn = !exValid || (exReadyGo && m1AllowIn)
    val exFire = exValid && exReadyGo && m1AllowIn

    val lane0ArchitecturalBranchEvent = exReg.valid(0) &&
        (exReg.lane(0).pipe.brType =/= BrType.NOP ||
         exReg.lane(0).pipe.predictedTaken)
    val branchLane1 = !lane0ArchitecturalBranchEvent && predictionCheck(1)
    val branchEvent = exFire && (predictionCheck(0) || predictionCheck(1))
    val branchRedirect = branchEvent &&
        Mux(branchLane1, branchWrong(1), branchWrong(0)) && !wbFlush
    when(branchRedirect && predictionCheck(0) && branchWrong(0)) {
        // lane 1 is younger than a lane-0 branch and is permitted to share the
        // packet only because it has no side effects before M1.
        exOut.valid(1) := false.B
    }
    val branchPipe = Mux(branchLane1, exReg.lane(1).pipe, exReg.lane(0).pipe)
    val branchActualTaken = Mux(branchLane1,
        exReg.lane(1).pipe.brType =/= BrType.NOP && branchTaken(1),
        exReg.lane(0).pipe.brType =/= BrType.NOP && branchTaken(0))
    val branchResolvedTarget = Mux(branchLane1, branchTarget(1), branchTarget(0))
    val branchNext = Mux(branchActualTaken, branchResolvedTarget,
        branchPipe.pc + 4.U)

    io.predictorUpdate.valid := branchEvent && !wbFlush
    io.predictorUpdate.pc := branchPipe.pc
    io.predictorUpdate.predictedHit := branchPipe.predictedHit
    io.predictorUpdate.history := branchPipe.predictedHistory
    io.predictorUpdate.isBranch := branchPipe.brType =/= BrType.NOP
    io.predictorUpdate.isConditional :=
        branchPipe.brType =/= BrType.NOP &&
        branchPipe.brType =/= BrType.JIRL &&
        branchPipe.brType =/= BrType.B &&
        branchPipe.brType =/= BrType.BL
    io.predictorUpdate.isCall := branchPipe.brType === BrType.BL ||
        (branchPipe.brType === BrType.JIRL && branchPipe.destReg === 1.U)
    io.predictorUpdate.isReturn := branchPipe.inst === "h4c000020".U
    io.predictorUpdate.taken := branchActualTaken
    io.predictorUpdate.target := branchResolvedTarget
    io.predictorUpdate.redirect := branchRedirect

    // Register the architectural redirect at the backend boundary.  The
    // backend itself kills younger EX/issue-buffer state on the event edge;
    // the following cycle is reserved for flushing the frontend queue and
    // redirecting fetch.  This removes the final M1->EX->frontend ready/flush
    // path without allowing a wrong-path packet to execute.
    val frontendFlushEvent = wbFlush || branchRedirect
    val frontendFlushReg = RegNext(frontendFlushEvent, false.B)
    val frontendTargetReg = RegEnable(
        Mux(wbFlush, wbTarget, branchNext), Config.START_PC,
        frontendFlushEvent)
    val frontendHistoryFlushReg = RegNext(wbFlush, false.B)
    io.frontendFlush := frontendFlushReg
    io.frontendTarget := frontendTargetReg
    io.flushPredictorHistory := frontendHistoryFlushReg

    // ---------------------------------------------------------------------
    // ID/Issue: two decoders, 4R2W RF, forwarding and serialization
    // ---------------------------------------------------------------------
    val srcAddr = Wire(Vec(4, UInt(5.W)))
    val decodedPipe = Wire(Vec(2, new DualLaneData()))
    val issueMemBank = Wire(Vec(2, Bool()))
    val issueCacheable = Wire(Vec(2, Bool()))

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
        srcAddr(2 * lane) := src1
        srcAddr(2 * lane + 1) := src2
        regfile.io.raddr(2 * lane) := src1
        regfile.io.raddr(2 * lane + 1) := src2

        decodedPipe(lane) := 0.U.asTypeOf(new DualLaneData())
        val pipe = decodedPipe(lane).pipe
        pipe.pc := io.fetchBits(lane).pc
        pipe.inst := io.fetchBits(lane).inst
        pipe.predictedHit := io.fetchBits(lane).predictedHit
        pipe.predictedTaken := io.fetchBits(lane).predictedTaken
        pipe.predictedTarget := io.fetchBits(lane).predictedTarget
        pipe.predictedHistory := io.fetchBits(lane).predictedHistory
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

        val interrupt = if (lane == 0) csr.io.hasInt else false.B
        pipe.hasException := interrupt || io.fetchBits(lane).hasException ||
            dec.hasException
        pipe.ecode := Mux(interrupt, ExcCode.INT,
            Mux(io.fetchBits(lane).hasException, io.fetchBits(lane).ecode,
                dec.ecode))
        pipe.esubcode := io.fetchBits(lane).esubcode
        decodedPipe(lane).src1Read := dec.src1_read
        decodedPipe(lane).src2Read := dec.src2_read
        decodedPipe(lane).serializing :=
            dec.isCsr || dec.tlbOp =/= TlbOp.NOP || dec.is_cacop ||
            dec.inst_ertn || dec.isLL || dec.isSC

        issue.io.in(lane).valid := io.fetchValid(lane)
        issue.io.in(lane).src1Read := dec.src1_read
        issue.io.in(lane).src1 := src1
        issue.io.in(lane).src2Read := dec.src2_read
        issue.io.in(lane).src2 := src2
        issue.io.in(lane).regWrite := dec.regWe
        issue.io.in(lane).dest := dec.destReg
        issue.io.in(lane).isMem := dec.resFromMem || dec.memWe || dec.is_cacop
        issue.io.in(lane).memBank := issueMemBank(lane)
        issue.io.in(lane).cacheable := issueCacheable(lane)
        issue.io.in(lane).isBranch := dec.brType =/= BrType.NOP
        issue.io.in(lane).isMdu := dec.mduOp =/= MduOp.NOP
        issue.io.in(lane).isMul := dec.mduOp === MduOp.MUL_W ||
            dec.mduOp === MduOp.MULH_W || dec.mduOp === MduOp.MULH_WU
        issue.io.in(lane).isDiv := dec.mduOp === MduOp.DIV_W ||
            dec.mduOp === MduOp.MOD_W || dec.mduOp === MduOp.DIV_WU ||
            dec.mduOp === MduOp.MOD_WU
        issue.io.in(lane).predictedTaken := io.fetchBits(lane).predictedTaken
        issue.io.in(lane).isSerializing := decodedPipe(lane).serializing
        issue.io.in(lane).hasException := pipe.hasException
    }

    case class Producer(
        valid: Bool, write: Bool, dest: UInt, result: UInt, ready: Bool)

    val issueQueueProducers = (1 to 0 by -1).flatMap { entry =>
        (1 to 0 by -1).map { lane =>
            val packet = issueQueue(entry)
            val pipe = packet.lane(lane).pipe
            Producer(issueQueueCount > entry.U && packet.valid(lane),
                pipe.regWriteEn && !pipe.hasException,
                pipe.destReg, 0.U, false.B)
        }
    }
    val exProducers = (1 to 0 by -1).map { lane =>
        val pipe = exReg.lane(lane).pipe
        Producer(exReg.valid(lane), pipe.regWriteEn && !pipe.hasException,
            pipe.destReg, exForwardResult(lane),
            !pipe.resFromMem && !pipe.isCsr && !pipe.resFromMulDiv)
    }
    val m1Producers = (1 to 0 by -1).map { lane =>
        // M1 forwarding must come from the stage register.  m1Out contains
        // the combinational M1 result being prepared for M2; using it here
        // let the EX multiplier/DCache/TLB path leak back into issue/popCount.
        val pipe = m1Reg.lane(lane).pipe
        Producer(m1Reg.valid(lane), pipe.regWriteEn && !pipe.hasException,
            pipe.destReg, pipe.ex_result, !pipe.resFromMem && !pipe.isCsr)
    }
    val m2Producers = (1 to 0 by -1).map { lane =>
        val pipe = m2Out.lane(lane).pipe
        Producer(m2Reg.valid(lane), pipe.regWriteEn && !pipe.hasException,
            pipe.destReg, pipe.ex_result,
            !pipe.isCsr && (!pipe.resFromMem ||
                m2Reg.lane(lane).dcacheDone || io.dcache(lane).data_ok))
    }
    val wbProducers = (1 to 0 by -1).map { lane =>
        Producer(wbReg.valid(lane), wbRfWe(lane),
            wbReg.lane(lane).pipe.destReg, wbFinalData(lane), true.B)
    }
    val producers = issueQueueProducers ++ exProducers ++
        m1Producers ++ m2Producers ++ wbProducers

    def resolveSource(addr: UInt, read: Bool, rfData: UInt): (UInt, Bool) = {
        var selected: Bool = false.B
        var value: UInt = rfData
        var blocked: Bool = false.B
        for (producer <- producers) {
            val hit = read && addr =/= 0.U && producer.valid &&
                producer.write && producer.dest === addr
            val take = hit && !selected
            value = Mux(take && producer.ready, producer.result, value)
            blocked = blocked || (take && !producer.ready)
            selected = selected || hit
        }
        (value, blocked)
    }

    val resolved = Wire(Vec(4, UInt(32.W)))
    val blocked = Wire(Vec(4, Bool()))
    for (port <- 0 until 4) {
        val lane = port / 2
        val read = io.fetchValid(lane) &&
            (if (port % 2 == 0) decodedPipe(lane).src1Read
                   else decodedPipe(lane).src2Read)
        val pair = resolveSource(srcAddr(port), read, regfile.io.rdata(port))
        resolved(port) := pair._1
        blocked(port) := pair._2
    }
    for (lane <- 0 until 2) {
        decodedPipe(lane).pipe.src1_value := resolved(2 * lane)
        decodedPipe(lane).pipe.src2_value := resolved(2 * lane + 1)

        val effectiveLow = resolved(2 * lane)(4, 0) +
            decodedPipe(lane).pipe.imm(4, 0)
        issueMemBank(lane) := effectiveLow(4)
        val directCached = csr.io.mmu_config.crmd.datm =/= 0.U
        val directMode = csr.io.mmu_config.crmd.da === 1.U &&
            csr.io.mmu_config.crmd.pg === 0.U
        val isMemOp = decodedPipe(lane).pipe.resFromMem ||
            decodedPipe(lane).pipe.memWe || decodedPipe(lane).pipe.is_cacop
        // The TLB lookup happens in M1.  Pair only accesses whose cacheability
        // is already certain in ID; mapped/DMW accesses remain conservative.
        issueCacheable(lane) := !isMemOp || (directMode && directCached)
    }

    val issuedBlocked = Wire(Vec(4, Bool()))
    for (port <- 0 until 4) {
        issuedBlocked(port) := blocked(port) && issue.io.issueValid(port / 2)
    }
    val issueHazard = issuedBlocked.asUInt.orR
    val serialHead = io.fetchValid(0) && decodedPipe(0).serializing
    val issueQueueSerial = (0 until 2).map { entry =>
        issueQueueCount > entry.U && ((issueQueue(entry).valid(0) &&
            issueQueue(entry).lane(0).serializing) ||
            (issueQueue(entry).valid(1) &&
            issueQueue(entry).lane(1).serializing))
    }.reduce(_ || _)
    val pipeSerialInFlight = Seq(exReg, m1Reg, m2Reg, wbReg).map { packet =>
        (packet.valid(0) && packet.lane(0).serializing) ||
        (packet.valid(1) && packet.lane(1).serializing)
    }.reduce(_ || _)
    val serialInFlight = issueQueueSerial || pipeSerialInFlight
    val serialBlocked = serialInFlight || (serialHead &&
        (issueQueueCount =/= 0.U || exValid || m1Valid || m2Valid || wbValid))
    val issueCanEnqueue = issueQueueCount =/= 2.U
    val issueFire = issueCanEnqueue && !issueHazard && !serialBlocked &&
        !frontendFlushReg && issue.io.issueCount =/= 0.U
    io.popCount := Mux(issueFire, issue.io.issueCount, 0.U)
    io.issueBlockMask := issue.io.blockMask

    val issuePacket = WireDefault(emptyPacket)
    for (lane <- 0 until 2) {
        issuePacket.valid(lane) := issue.io.issueValid(lane)
        issuePacket.lane(lane) := decodedPipe(lane)
    }

    // ---------------------------------------------------------------------
    // Stage register updates.  Retiring flush has global priority; a branch
    // redirect removes only instructions younger than the EX branch.
    // ---------------------------------------------------------------------
    val m1Fire = m1Valid && m1ReadyGo && m2AllowIn
    val issueDeq = issueQueueCount =/= 0.U && exAllowIn &&
        !frontendFlushReg
    io.icacheInvalidateAll := m1Fire && (0 until 2).map { lane =>
        m1IcacheCacop(lane) && !m1MmuFault(lane)
    }.reduce(_ || _)

    // The registered frontend flush is also the credit invalidation point.
    // During its one-cycle delay EX is killed immediately by wbFlush/redirect;
    // on the flush cycle both enqueue and dequeue are disabled.
    when(frontendFlushReg) {
        issueQueueCount := 0.U
    }.otherwise {
        switch(Cat(issueFire, issueDeq)) {
            is("b10".U) {
                issueQueueCount := issueQueueCount + 1.U
            }
            is("b01".U) {
                issueQueueCount := issueQueueCount - 1.U
            }
        }
    }

    // Payload bits need no explicit flush: count=0 invalidates both entries.
    // Keeping these writes outside the redirect-priority block prevents the
    // long branch/TLB/cache cone from becoming the CE of every payload FF.
    when(issueFire) {
        when(issueDeq || issueQueueCount === 0.U) {
            issueQueue(0) := issuePacket
        }.otherwise {
            issueQueue(1) := issuePacket
        }
    }.elsewhen(issueDeq && issueQueueCount === 2.U) {
        issueQueue(0) := issueQueue(1)
    }

    when(wbFlush) {
        wbReg := emptyPacket
        m2Reg := emptyPacket
        m1Reg := emptyPacket
        exReg := emptyPacket
        divStarted := false.B
        divFinished := false.B
    }.otherwise {
        // WB always retires its current packet.
        wbReg := Mux(m2Valid && m2ReadyGo, m2Out, emptyPacket)

        when(m2AllowIn) {
            m2Reg := Mux(m1Fire, m1Out, emptyPacket)
        }.otherwise {
            // Preserve a response from either bank while the other lane is
            // still waiting.
            m2Reg := m2Out
        }
        when(m1AllowIn) {
            m1Reg := Mux(exFire, exOut, emptyPacket)
        }
        when(branchRedirect) {
            exReg := emptyPacket
        }.elsewhen(exAllowIn) {
            exReg := Mux(issueDeq, issueQueue(0), emptyPacket)
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

    // Lockstep invariants are part of the architectural contract: lane 1
    // cannot exist without its older lane 0.  Dual memory packets must target
    // different cache banks; same-bank pairs are serialized by issue.
    for (packet <- Seq(exReg, m1Reg, m2Reg, wbReg)) {
        assert(!packet.valid(1) || packet.valid(0),
            "dual packet lane1 must never overtake lane0")
        val mem0 = packet.valid(0) &&
            (packet.lane(0).pipe.resFromMem || packet.lane(0).pipe.memWe)
        val mem1 = packet.valid(1) &&
            (packet.lane(1).pipe.resFromMem || packet.lane(1).pipe.memWe)
        when(mem0 && mem1) {
            val bank0 = (packet.lane(0).pipe.src1_value +
                packet.lane(0).pipe.imm)(4)
            val bank1 = (packet.lane(1).pipe.src1_value +
                packet.lane(1).pipe.imm)(4)
            assert(bank0 =/= bank1,
                "dual LSU packet contains a same-bank pair")
        }
    }
    for (entry <- 0 until 2) {
        when(issueQueueCount > entry.U) {
            assert(!issueQueue(entry).valid(1) ||
                issueQueue(entry).valid(0),
                "buffered dual packet lane1 must never overtake lane0")
        }
    }
    when(serialInFlight) {
        assert(io.popCount === 0.U,
            "no younger instruction may issue behind a serializing operation")
    }
}
