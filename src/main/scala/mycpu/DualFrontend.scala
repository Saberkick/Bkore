package mycpu

import chisel3._
import chisel3.util._

/**
  * Three logical fetch stages around a blocking line cache:
  * F1 selects PC and predicts, F2 translates/requests, F3 aligns the returned
  * line and feeds an eight-entry two-wide instruction queue.
  */
class DualFrontend extends Module {
    val io = IO(new Bundle {
        val flush = Input(Bool())
        val flushTarget = Input(UInt(32.W))
        val flushPredictorHistory = Input(Bool())
        val predictorUpdate = Input(new DualPredictorUpdate())

        val popCount = Input(UInt(2.W))
        val outValid = Output(Vec(2, Bool()))
        val outBits  = Output(Vec(2, new DualFetchEntry()))
        val queueCount = Output(UInt(4.W))

        val cache = Flipped(new DualICacheCpuIO())

        val mmuConfig = Input(new MmuConfig())
        val tlbVppn = Output(UInt(19.W))
        val tlbVaBit12 = Output(Bool())
        val tlbAsid = Output(UInt(10.W))
        val tlbFound = Input(Bool())
        val tlbPpn = Input(UInt(20.W))
        val tlbPs = Input(UInt(6.W))
        val tlbPlv = Input(UInt(2.W))
        val tlbMat = Input(UInt(2.W))
        val tlbV = Input(Bool())
    })

    val predictor = Module(new DualBranchPredictor())
    val queue = Module(new DualInstructionQueue())

    val pc = RegInit(Config.START_PC)
    predictor.io.reqPc(0) := pc
    predictor.io.reqPc(1) := pc + 4.U
    predictor.io.update := io.predictorUpdate
    predictor.io.flushHistory := io.flushPredictorHistory

    io.tlbVppn := pc(31, 13)
    io.tlbVaBit12 := pc(12)
    io.tlbAsid := io.mmuConfig.asid.asid

    val direct = io.mmuConfig.crmd.da === 1.U && io.mmuConfig.crmd.pg === 0.U
    val dmw0Hit = io.mmuConfig.crmd.pg === 1.U && !io.mmuConfig.crmd.da.asBool &&
        pc(31, 29) === io.mmuConfig.dmw0.vseg &&
        ((io.mmuConfig.crmd.plv === 0.U && io.mmuConfig.dmw0.plv0.asBool) ||
         (io.mmuConfig.crmd.plv === 3.U && io.mmuConfig.dmw0.plv3.asBool))
    val dmw1Hit = io.mmuConfig.crmd.pg === 1.U && !io.mmuConfig.crmd.da.asBool &&
        pc(31, 29) === io.mmuConfig.dmw1.vseg &&
        ((io.mmuConfig.crmd.plv === 0.U && io.mmuConfig.dmw1.plv0.asBool) ||
         (io.mmuConfig.crmd.plv === 3.U && io.mmuConfig.dmw1.plv3.asBool))
    val dmwHit = dmw0Hit || dmw1Hit
    val dmwPa = Mux(dmw0Hit, Cat(io.mmuConfig.dmw0.pseg, pc(28, 0)),
        Cat(io.mmuConfig.dmw1.pseg, pc(28, 0)))
    val tlbPa = Mux(io.tlbPs === 12.U, Cat(io.tlbPpn, pc(11, 0)),
        Cat(io.tlbPpn(19, 9), pc(20, 0)))
    val pa = Mux(direct, pc, Mux(dmwHit, dmwPa,
        Mux(io.tlbFound && io.tlbV, tlbPa, pc)))

    val dmwMat = Mux(dmw0Hit, io.mmuConfig.dmw0.mat, io.mmuConfig.dmw1.mat)
    val currentMat = Mux(direct, io.mmuConfig.crmd.datf,
        Mux(dmwHit, dmwMat, io.tlbMat))
    val uncached = currentMat === 0.U

    val mapped = io.mmuConfig.crmd.pg === 1.U && !io.mmuConfig.crmd.da.asBool && !dmwHit
    val excRefill = mapped && !io.tlbFound
    val excPif = mapped && io.tlbFound && !io.tlbV
    val excPpi = mapped && io.tlbFound && io.tlbV &&
        io.mmuConfig.crmd.plv > io.tlbPlv
    val excAlign = pc(1, 0) =/= 0.U
    val fetchException = excAlign || excRefill || excPif || excPpi
    val fetchEcode = Mux(excAlign, ExcCode.ADEF,
        Mux(excRefill, ExcCode.TLBR,
        Mux(excPif, ExcCode.PIF,
        Mux(excPpi, ExcCode.PPI, 0.U))))

    val slot0Taken = predictor.io.result(0).taken && !fetchException
    val lineHasSecond = pc(3, 2) =/= 3.U
    val allowSecond = lineHasSecond && !uncached && !slot0Taken && !fetchException
    val slot1Taken = predictor.io.result(1).taken && allowSecond
    val requestCount = Mux(allowSecond, 2.U(2.W), 1.U(2.W))
    val predictedNext = Mux(slot0Taken, predictor.io.result(0).target,
        Mux(slot1Taken, predictor.io.result(1).target,
            pc + Mux(allowSecond, 8.U, 4.U)))

    val waitResponse = RegInit(false.B)
    val discardResponse = RegInit(false.B)
    val pendingSynthetic = RegInit(false.B)
    val pendingPc = RegInit(Config.START_PC)
    val pendingCount = RegInit(0.U(2.W))
    val pendingException = RegInit(false.B)
    val pendingEcode = RegInit(0.U(6.W))
    val pendingTaken = RegInit(VecInit(Seq.fill(2)(false.B)))
    val pendingTarget = RegInit(VecInit(Seq.fill(2)(0.U(32.W))))

    val buffer = Reg(Vec(2, new DualFetchEntry()))
    val bufferCount = RegInit(0.U(2.W))

    val words = Wire(Vec(4, UInt(32.W)))
    for (idx <- 0 until 4) {
        words(idx) := io.cache.line(32 * idx + 31, 32 * idx)
    }

    val responseEntries = Wire(Vec(2, new DualFetchEntry()))
    for (slot <- 0 until 2) {
        val selectedWord = if (slot == 0) {
            words(pendingPc(3, 2))
        } else {
            MuxLookup(pendingPc(3, 2), words(0))(Seq(
                0.U -> words(1),
                1.U -> words(2),
                2.U -> words(3),
                3.U -> words(0)
            ))
        }
        responseEntries(slot) := 0.U.asTypeOf(new DualFetchEntry())
        responseEntries(slot).pc := pendingPc + (slot * 4).U
        responseEntries(slot).inst := Mux(pendingException, "h03400000".U,
            selectedWord)
        responseEntries(slot).predictedTaken := pendingTaken(slot)
        responseEntries(slot).predictedTarget := pendingTarget(slot)
        responseEntries(slot).hasException := pendingException
        responseEntries(slot).ecode := pendingEcode
    }

    val realResponse = waitResponse && !discardResponse &&
        (pendingSynthetic || io.cache.dataOk)
    val sourceCount = Mux(bufferCount =/= 0.U, bufferCount,
        Mux(realResponse, pendingCount, 0.U))
    val sourceEntries = Wire(Vec(2, new DualFetchEntry()))
    sourceEntries := Mux(bufferCount =/= 0.U, buffer, responseEntries)

    queue.io.flush := io.flush
    queue.io.enqValid(0) := sourceCount >= 1.U
    queue.io.enqValid(1) := sourceCount >= 2.U
    queue.io.enqBits := sourceEntries
    queue.io.popCount := io.popCount
    io.outValid := queue.io.deqValid
    io.outBits := queue.io.deqBits
    io.queueCount := queue.io.count

    val pushed = Mux(queue.io.enqValid(1) && queue.io.enqReady(1), 2.U(2.W),
        Mux(queue.io.enqValid(0) && queue.io.enqReady(0), 1.U(2.W), 0.U(2.W)))
    val remainingCount = sourceCount - pushed
    val sourceDrained = sourceCount =/= 0.U && remainingCount === 0.U

    val requestSlotFree = !waitResponse || (realResponse && sourceDrained)
    val outputSlotFree = bufferCount === 0.U || sourceDrained
    val mayRequest = requestSlotFree && outputSlotFree && !discardResponse && !io.flush
    // Translation/alignment faults are architectural fetch results, not
    // memory transactions.  Synthesize their queue entries locally so an
    // exception cannot deadlock behind an unrelated ICache miss.
    val syntheticRequest = mayRequest && fetchException
    io.cache.valid := mayRequest && !fetchException
    val safePa = Mux(fetchException, Config.START_PC, pa)
    io.cache.index := safePa(11, 4)
    io.cache.tag := safePa(31, 12)
    io.cache.offset := safePa(3, 2)
    io.cache.uncached := uncached
    val requestFire = (io.cache.valid && io.cache.addrOk) || syntheticRequest

    when(io.flush) {
        pc := io.flushTarget
        waitResponse := false.B
        pendingSynthetic := false.B
        bufferCount := 0.U
        when(waitResponse && !pendingSynthetic && !io.cache.dataOk) {
            discardResponse := true.B
        }
    }.otherwise {
        when(requestFire) {
            pc := predictedNext
            waitResponse := true.B
            pendingSynthetic := syntheticRequest
            pendingPc := pc
            pendingCount := requestCount
            pendingException := fetchException
            pendingEcode := fetchEcode
            pendingTaken(0) := slot0Taken
            pendingTaken(1) := slot1Taken
            pendingTarget(0) := predictor.io.result(0).target
            pendingTarget(1) := predictor.io.result(1).target
        }.elsewhen(realResponse) {
            waitResponse := false.B
            pendingSynthetic := false.B
        }

        when(sourceCount =/= 0.U) {
            when(remainingCount === 0.U) {
                bufferCount := 0.U
            }.elsewhen(bufferCount =/= 0.U) {
                buffer(0) := Mux(pushed === 1.U, buffer(1), buffer(0))
                bufferCount := remainingCount
            }.otherwise {
                buffer(0) := Mux(pushed === 1.U, responseEntries(1), responseEntries(0))
                buffer(1) := responseEntries(1)
                bufferCount := remainingCount
            }
        }
    }

    when(discardResponse && io.cache.dataOk) {
        discardResponse := false.B
    }
}
