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

    val mulFinished = RegInit(false.B)
    val mulResult = RegInit(0.U(32.W))
    val mulSigned = exReg.lane(0).pipe.mduOp === MduOp.MULH_W
    val mulProduct = Mux(mulSigned,
        (exReg.lane(0).pipe.src1_value.asSInt *
         exReg.lane(0).pipe.src2_value.asSInt).asUInt,
        exReg.lane(0).pipe.src1_value * exReg.lane(0).pipe.src2_value)

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
    val currentMduResult = Mux(exIsMul, mulResult,
        Mux(divider.io.done, liveDivResult, divResult))

    val exReadyGo = !exValid ||
        (!exIsMul && !exIsDiv) ||
        (exIsMul && mulFinished) ||
        (exIsDiv && (divFinished || divider.io.done))

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
            Mux(pipe.resFromMulDiv, currentMduResult, baseResult)))))

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
    val m1Selected1 = m1Reg.valid(1) && (
        m1Reg.lane(1).pipe.resFromMem || m1Reg.lane(1).pipe.memWe ||
        m1Reg.lane(1).pipe.is_cacop || m1Reg.lane(1).pipe.tlbOp =/= TlbOp.NOP)
    val m1Selected = Mux(m1Selected1, m1Reg.lane(1), m1Reg.lane(0))
    val m1Pipe = m1Selected.pipe
    val m1Va = m1Pipe.ex_result
    val m1IsInv = m1Pipe.tlbOp === TlbOp.INV
    val m1IsSearch = m1Pipe.tlbOp === TlbOp.SRCH

    val wbInvVppn = wbReg.lane(0).pipe.src2_value(31, 13)
    val wbInvAsid = wbReg.lane(0).pipe.src1_value(9, 0)
    io.tlbVppn := Mux(wbInvtlb, wbInvVppn,
        Mux(m1IsInv, m1Pipe.src2_value(31, 13),
        Mux(m1IsSearch, csr.io.mmu_config.tlbehi.vppn, m1Va(31, 13))))
    io.tlbVaBit12 := m1Va(12)
    io.tlbAsid := Mux(wbInvtlb, wbInvAsid,
        Mux(m1IsInv, m1Pipe.src1_value(9, 0), csr.io.mmu_config.asid.asid))

    val m1Direct = csr.io.mmu_config.crmd.da === 1.U &&
        csr.io.mmu_config.crmd.pg === 0.U
    val m1Dmw0Hit = csr.io.mmu_config.crmd.pg === 1.U &&
        !csr.io.mmu_config.crmd.da.asBool &&
        m1Va(31, 29) === csr.io.mmu_config.dmw0.vseg &&
        ((csr.io.mmu_config.crmd.plv === 0.U && csr.io.mmu_config.dmw0.plv0.asBool) ||
         (csr.io.mmu_config.crmd.plv === 3.U && csr.io.mmu_config.dmw0.plv3.asBool))
    val m1Dmw1Hit = csr.io.mmu_config.crmd.pg === 1.U &&
        !csr.io.mmu_config.crmd.da.asBool &&
        m1Va(31, 29) === csr.io.mmu_config.dmw1.vseg &&
        ((csr.io.mmu_config.crmd.plv === 0.U && csr.io.mmu_config.dmw1.plv0.asBool) ||
         (csr.io.mmu_config.crmd.plv === 3.U && csr.io.mmu_config.dmw1.plv3.asBool))
    val m1DmwHit = m1Dmw0Hit || m1Dmw1Hit
    val m1DmwPa = Mux(m1Dmw0Hit,
        Cat(csr.io.mmu_config.dmw0.pseg, m1Va(28, 0)),
        Cat(csr.io.mmu_config.dmw1.pseg, m1Va(28, 0)))
    val m1TlbPa = Mux(io.tlbPs === 12.U, Cat(io.tlbPpn, m1Va(11, 0)),
        Cat(io.tlbPpn(19, 9), m1Va(20, 0)))
    val m1Pa = Mux(m1Direct, m1Va,
        Mux(m1DmwHit, m1DmwPa,
        Mux(io.tlbFound && io.tlbV, m1TlbPa, m1Va)))
    val m1Mapped = csr.io.mmu_config.crmd.pg === 1.U &&
        !csr.io.mmu_config.crmd.da.asBool && !m1DmwHit
    val m1Load = m1Pipe.resFromMem && !m1Pipe.hasException
    val m1Store = m1Pipe.memWe && !m1Pipe.hasException
    val m1DcacheCacop = m1Pipe.is_cacop && m1Pipe.cacop_op(2, 0) === 1.U
    val m1IcacheCacop = m1Pipe.is_cacop && m1Pipe.cacop_op(2, 0) === 0.U
    val m1IsAccess = m1Load || m1Store || m1DcacheCacop
    val m1Refill = m1IsAccess && m1Mapped && !io.tlbFound
    val m1Ppi = m1IsAccess && m1Mapped && io.tlbFound && io.tlbV &&
        csr.io.mmu_config.crmd.plv > io.tlbPlv
    val m1Pil = (m1Load || m1DcacheCacop) && m1Mapped &&
        io.tlbFound && !io.tlbV
    val m1Pis = m1Store && m1Mapped && io.tlbFound && !io.tlbV
    val m1Pme = m1Store && m1Mapped && io.tlbFound && io.tlbV &&
        !m1Ppi && !io.tlbD
    val m1MmuFault = m1Refill || m1Ppi || m1Pil || m1Pis || m1Pme
    val m1FaultCode =
        (Fill(6, m1Refill) & ExcCode.TLBR) |
        (Fill(6, m1Ppi) & ExcCode.PPI) |
        (Fill(6, m1Pil) & ExcCode.PIL) |
        (Fill(6, m1Pis) & ExcCode.PIS) |
        (Fill(6, m1Pme) & ExcCode.PME)

    val m1DmwMat = Mux(m1Dmw0Hit, csr.io.mmu_config.dmw0.mat,
        csr.io.mmu_config.dmw1.mat)
    val m1Mat = Mux(m1Direct, csr.io.mmu_config.crmd.datm,
        Mux(m1DmwHit, m1DmwMat, io.tlbMat))
    val m1Uncached = m1Mat === 0.U

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
                    Cat(!io.tlbFound, 0.U(27.W), io.tlbIndex)
                m1Out.lane(lane).pipe.aux_data :=
                    Mux(io.tlbFound, "h8000000f".U, "h80000000".U)
            }
            m1Out.lane(lane).waitDcache := m1IsAccess && !m1MmuFault
        }
    }

    val dcacheRequest = m1Valid && m1IsAccess && !m1MmuFault
    // m2AllowIn is declared below; a wire breaks the declaration order.
    val m2AllowWire = Wire(Bool())
    io.dcache.valid := dcacheRequest && m2AllowWire
    io.dcache.op := m1Store
    val cacopIndex = m1Pipe.is_cacop && m1Pipe.cacop_op(4, 3) =/= "b10".U
    val dcacheAddress = Cat(Mux(cacopIndex, m1Va(31, 12), m1Pa(31, 12)),
        m1Va(11, 0))
    io.dcache.index := dcacheAddress(11, 4)
    io.dcache.tag := dcacheAddress(31, 12)
    io.dcache.offset := dcacheAddress(3, 0)
    val m1Word = m1Pipe.lsOp === LsOp.LD_W || m1Pipe.lsOp === LsOp.ST_W
    val m1Half = m1Pipe.lsOp === LsOp.LD_H || m1Pipe.lsOp === LsOp.LD_HU ||
        m1Pipe.lsOp === LsOp.ST_H
    val byteMask = "b0001".U(4.W) << m1Va(1, 0)
    val halfMask = Mux(m1Va(1), "b1100".U(4.W), "b0011".U(4.W))
    io.dcache.wstrb := Mux(m1Store,
        Mux(m1Word, "b1111".U, Mux(m1Half, halfMask, byteMask)), 0.U)
    io.dcache.wdata := Mux(m1Word, m1Pipe.src2_value,
        Mux(m1Half, Fill(2, m1Pipe.src2_value(15, 0)),
            Fill(4, m1Pipe.src2_value(7, 0))))
    io.dcache.uncached := m1Uncached
    io.dcache.cacop_en := m1DcacheCacop
    io.dcache.cacop_op := m1Pipe.cacop_op(4, 3)

    // ---------------------------------------------------------------------
    // M2: blocking DCache completion and load extraction
    // ---------------------------------------------------------------------
    val m2Wait = (m2Reg.valid(0) && m2Reg.lane(0).waitDcache) ||
        (m2Reg.valid(1) && m2Reg.lane(1).waitDcache)
    val m2ReadyGo = !m2Valid || !m2Wait || io.dcache.data_ok
    val m2AllowIn = !m2Valid || m2ReadyGo
    m2AllowWire := m2AllowIn

    val m2Out = WireDefault(m2Reg)
    for (lane <- 0 until 2) {
        val pipe = m2Reg.lane(lane).pipe
        val offset = pipe.ex_result(1, 0)
        val byte = MuxLookup(offset, 0.U(8.W))(Seq(
            0.U -> io.dcache.rdata(7, 0),
            1.U -> io.dcache.rdata(15, 8),
            2.U -> io.dcache.rdata(23, 16),
            3.U -> io.dcache.rdata(31, 24)
        ))
        val half = Mux(offset(1), io.dcache.rdata(31, 16),
            io.dcache.rdata(15, 0))
        val loadResult = MuxLookup(pipe.lsOp, io.dcache.rdata)(Seq(
            LsOp.LD_B -> Cat(Fill(24, byte(7)), byte),
            LsOp.LD_BU -> Cat(0.U(24.W), byte),
            LsOp.LD_H -> Cat(Fill(16, half(15)), half),
            LsOp.LD_HU -> Cat(0.U(16.W), half),
            LsOp.LD_W -> io.dcache.rdata
        ))
        when(m2Reg.valid(lane) && pipe.resFromMem && io.dcache.data_ok) {
            m2Out.lane(lane).pipe.ex_result := loadResult
        }
    }

    // ---------------------------------------------------------------------
    // Elastic stage readiness and branch event
    // ---------------------------------------------------------------------
    val m1NeedsDcache = m1Valid && m1IsAccess && !m1MmuFault
    val m1ReadyGo = !m1Valid || !m1NeedsDcache || io.dcache.addr_ok
    val m1AllowIn = !m1Valid || (m1ReadyGo && m2AllowIn)
    val exAllowIn = !exValid || (exReadyGo && m1AllowIn)
    val exFire = exValid && exReadyGo && m1AllowIn

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

    io.frontendFlush := wbFlush || branchRedirect
    io.frontendTarget := Mux(wbFlush, wbTarget, branchNext)
    io.flushPredictorHistory := wbFlush

    // ---------------------------------------------------------------------
    // ID/Issue: two decoders, 4R2W RF, forwarding and serialization
    // ---------------------------------------------------------------------
    val srcAddr = Wire(Vec(4, UInt(5.W)))
    val decodedPipe = Wire(Vec(2, new DualLaneData()))

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
        pipe.predictedTaken := io.fetchBits(lane).predictedTaken
        pipe.predictedTarget := io.fetchBits(lane).predictedTarget
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
        issue.io.in(lane).isBranch := dec.brType =/= BrType.NOP
        issue.io.in(lane).isMdu := dec.mduOp =/= MduOp.NOP
        issue.io.in(lane).isSerializing := decodedPipe(lane).serializing
        issue.io.in(lane).hasException := pipe.hasException
    }

    case class Producer(
        valid: Bool, write: Bool, dest: UInt, result: UInt, ready: Bool)

    val exProducers = (1 to 0 by -1).map { lane =>
        val pipe = exOut.lane(lane).pipe
        Producer(exReg.valid(lane), pipe.regWriteEn && !pipe.hasException,
            pipe.destReg, pipe.ex_result,
            !pipe.resFromMem && !pipe.isCsr &&
                (!pipe.resFromMulDiv || exReadyGo))
    }
    val m1Producers = (1 to 0 by -1).map { lane =>
        val pipe = m1Out.lane(lane).pipe
        Producer(m1Reg.valid(lane), pipe.regWriteEn && !pipe.hasException,
            pipe.destReg, pipe.ex_result, !pipe.resFromMem && !pipe.isCsr)
    }
    val m2Producers = (1 to 0 by -1).map { lane =>
        val pipe = m2Out.lane(lane).pipe
        Producer(m2Reg.valid(lane), pipe.regWriteEn && !pipe.hasException,
            pipe.destReg, pipe.ex_result,
            !pipe.isCsr && (!pipe.resFromMem || io.dcache.data_ok))
    }
    val wbProducers = (1 to 0 by -1).map { lane =>
        Producer(wbReg.valid(lane), wbRfWe(lane),
            wbReg.lane(lane).pipe.destReg, wbFinalData(lane), true.B)
    }
    val producers = exProducers ++ m1Producers ++ m2Producers ++ wbProducers

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
        val laneIssued = issue.io.issueValid(lane)
        val read = laneIssued && (if (port % 2 == 0) decodedPipe(lane).src1Read
                   else decodedPipe(lane).src2Read)
        val pair = resolveSource(srcAddr(port), read, regfile.io.rdata(port))
        resolved(port) := pair._1
        blocked(port) := pair._2
    }
    for (lane <- 0 until 2) {
        decodedPipe(lane).pipe.src1_value := resolved(2 * lane)
        decodedPipe(lane).pipe.src2_value := resolved(2 * lane + 1)
    }

    val issueHazard = blocked.asUInt.orR
    val serialHead = io.fetchValid(0) && decodedPipe(0).serializing
    val serialInFlight = Seq(exReg, m1Reg, m2Reg, wbReg).map { packet =>
        (packet.valid(0) && packet.lane(0).serializing) ||
        (packet.valid(1) && packet.lane(1).serializing)
    }.reduce(_ || _)
    val serialBlocked = serialInFlight ||
        (serialHead && (exValid || m1Valid || m2Valid || wbValid))
    val issueFire = exAllowIn && !issueHazard && !serialBlocked &&
        !wbFlush && !branchRedirect && issue.io.issueCount =/= 0.U
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
    io.icacheInvalidateAll := m1Fire && m1IcacheCacop && !m1MmuFault

    when(wbFlush) {
        wbReg := emptyPacket
        m2Reg := emptyPacket
        m1Reg := emptyPacket
        exReg := emptyPacket
        mulFinished := false.B
        divStarted := false.B
        divFinished := false.B
    }.otherwise {
        // WB always retires its current packet.
        wbReg := Mux(m2Valid && m2ReadyGo, m2Out, emptyPacket)

        when(m2AllowIn) {
            m2Reg := Mux(m1Fire, m1Out, emptyPacket)
        }
        when(m1AllowIn) {
            m1Reg := Mux(exFire, exOut, emptyPacket)
        }
        when(branchRedirect) {
            exReg := emptyPacket
        }.elsewhen(exAllowIn) {
            exReg := Mux(issueFire, issuePacket, emptyPacket)
        }

        when(exIsMul && !mulFinished) {
            mulResult := Mux(
                exReg.lane(0).pipe.mduOp === MduOp.MUL_W,
                mulProduct(31, 0), mulProduct(63, 32))
            mulFinished := true.B
        }
        when(divider.io.enable && divider.io.ready) {
            divStarted := true.B
        }
        when(divider.io.done) {
            divResult := liveDivResult
            divFinished := true.B
        }
        when(exFire) {
            mulFinished := false.B
            divStarted := false.B
            divFinished := false.B
        }
    }

    // Lockstep invariants are part of the architectural contract: lane 1
    // cannot exist without its older lane 0, and the shared LSU can see at
    // most one operation in any packet.
    for (packet <- Seq(exReg, m1Reg, m2Reg, wbReg)) {
        assert(!packet.valid(1) || packet.valid(0),
            "dual packet lane1 must never overtake lane0")
        val mem0 = packet.valid(0) &&
            (packet.lane(0).pipe.resFromMem || packet.lane(0).pipe.memWe)
        val mem1 = packet.valid(1) &&
            (packet.lane(1).pipe.resFromMem || packet.lane(1).pipe.memWe)
        assert(!(mem0 && mem1), "dual packet contains two LSU operations")
    }
    when(serialInFlight) {
        assert(io.popCount === 0.U,
            "no younger instruction may issue behind a serializing operation")
    }
}
