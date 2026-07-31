package mycpu

import chisel3._
import chisel3.util._

/**
  * Registered F0/F1/F2 frontend.
  *
  * F0 launches the predictor, ICache index metadata, and TLB lookup.  F1 uses
  * only registered translation metadata plus synchronous predictor outputs.
  * F2 aligns the registered cache response before it enters FetchQ.
  */
class DualFrontend extends Module {
    val io = IO(new Bundle {
        val flush = Input(Bool())
        val flushTarget = Input(UInt(32.W))
        val flushPredictorHistory = Input(Bool())
        val predictorUpdate = Input(new DualPredictorUpdate())

        val popCount = Input(UInt(2.W))
        val outValid = Output(Vec(2, Bool()))
        val outBits = Output(Vec(2, new DualFetchEntry()))
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

    // ---------------------------------------------------------------------
    // F0: PC and parallel predictor/TLB launch
    // ---------------------------------------------------------------------
    val pc = RegInit(Config.START_PC)
    predictor.io.reqPc(0) := pc
    predictor.io.reqPc(1) := pc + 4.U
    predictor.io.update := io.predictorUpdate
    predictor.io.flushHistory := io.flushPredictorHistory

    io.tlbVppn := pc(31, 13)
    io.tlbVaBit12 := pc(12)
    io.tlbAsid := io.mmuConfig.asid.asid

    val f0Direct = io.mmuConfig.crmd.da === 1.U &&
        io.mmuConfig.crmd.pg === 0.U
    val f0Dmw0Hit = io.mmuConfig.crmd.pg === 1.U &&
        !io.mmuConfig.crmd.da.asBool &&
        pc(31, 29) === io.mmuConfig.dmw0.vseg &&
        ((io.mmuConfig.crmd.plv === 0.U &&
          io.mmuConfig.dmw0.plv0.asBool) ||
         (io.mmuConfig.crmd.plv === 3.U &&
          io.mmuConfig.dmw0.plv3.asBool))
    val f0Dmw1Hit = io.mmuConfig.crmd.pg === 1.U &&
        !io.mmuConfig.crmd.da.asBool &&
        pc(31, 29) === io.mmuConfig.dmw1.vseg &&
        ((io.mmuConfig.crmd.plv === 0.U &&
          io.mmuConfig.dmw1.plv0.asBool) ||
         (io.mmuConfig.crmd.plv === 3.U &&
          io.mmuConfig.dmw1.plv3.asBool))
    val f0DmwHit = f0Dmw0Hit || f0Dmw1Hit
    val f0DmwPa = Mux(f0Dmw0Hit,
        Cat(io.mmuConfig.dmw0.pseg, pc(28, 0)),
        Cat(io.mmuConfig.dmw1.pseg, pc(28, 0)))
    val f0TlbPa = Mux(io.tlbPs === 12.U,
        Cat(io.tlbPpn, pc(11, 0)),
        Cat(io.tlbPpn(19, 9), pc(20, 0)))
    val f0Pa = Mux(f0Direct, pc,
        Mux(f0DmwHit, f0DmwPa,
            Mux(io.tlbFound && io.tlbV, f0TlbPa, pc)))

    val f0DmwMat = Mux(f0Dmw0Hit,
        io.mmuConfig.dmw0.mat, io.mmuConfig.dmw1.mat)
    val f0Mat = Mux(f0Direct, io.mmuConfig.crmd.datf,
        Mux(f0DmwHit, f0DmwMat, io.tlbMat))
    val f0Uncached = f0Mat === 0.U

    val f0Mapped = io.mmuConfig.crmd.pg === 1.U &&
        !io.mmuConfig.crmd.da.asBool && !f0DmwHit
    val f0ExcRefill = f0Mapped && !io.tlbFound
    val f0ExcPif = f0Mapped && io.tlbFound && !io.tlbV
    val f0ExcPpi = f0Mapped && io.tlbFound && io.tlbV &&
        io.mmuConfig.crmd.plv > io.tlbPlv
    val f0ExcAlign = pc(1, 0) =/= 0.U
    val f0Exception =
        f0ExcAlign || f0ExcRefill || f0ExcPif || f0ExcPpi
    val f0Ecode = Mux(f0ExcAlign, ExcCode.ADEF,
        Mux(f0ExcRefill, ExcCode.TLBR,
        Mux(f0ExcPif, ExcCode.PIF,
        Mux(f0ExcPpi, ExcCode.PPI, 0.U))))

    // F1 datapath payload is deliberately unreset.
    val f1Valid = RegInit(false.B)
    val f1Pc = Reg(UInt(32.W))
    val f1Pa = Reg(UInt(32.W))
    val f1Uncached = Reg(Bool())
    val f1Exception = Reg(Bool())
    val f1Ecode = Reg(UInt(6.W))

    // ---------------------------------------------------------------------
    // Outstanding F2/cache context and response buffering
    // ---------------------------------------------------------------------
    val waitResponse = RegInit(false.B)
    val discardResponse = RegInit(false.B)
    val pendingSynthetic = RegInit(false.B)
    val pendingPc = Reg(UInt(32.W))
    val pendingCount = Reg(UInt(2.W))
    val pendingException = Reg(Bool())
    val pendingEcode = Reg(UInt(6.W))
    val pendingTaken = Reg(Vec(2, Bool()))
    val pendingTarget = Reg(Vec(2, UInt(32.W)))
    val pendingHit = Reg(Vec(2, Bool()))
    val pendingWay = Reg(Vec(2, Bool()))
    val pendingCall = Reg(Vec(2, Bool()))
    val pendingReturn = Reg(Vec(2, Bool()))
    val pendingHistorySpeculated = Reg(Vec(2, Bool()))
    val pendingGhr = Reg(Vec(2, UInt(8.W)))
    val pendingRasSp = Reg(Vec(2, UInt(4.W)))

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
        responseEntries(slot).inst := Mux(
            pendingException, "h03400000".U, selectedWord)
        responseEntries(slot).predictedTaken := pendingTaken(slot)
        responseEntries(slot).predictedTarget := pendingTarget(slot)
        responseEntries(slot).predictedHit := pendingHit(slot)
        responseEntries(slot).predictedWay := pendingWay(slot)
        responseEntries(slot).predictedCall := pendingCall(slot)
        responseEntries(slot).predictedReturn := pendingReturn(slot)
        responseEntries(slot).historySpeculated :=
            pendingHistorySpeculated(slot)
        responseEntries(slot).ghrSnapshot := pendingGhr(slot)
        responseEntries(slot).rasSpSnapshot := pendingRasSp(slot)
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

    val pushed = Mux(
        queue.io.enqValid(1) && queue.io.enqReady(1), 2.U(2.W),
        Mux(queue.io.enqValid(0) && queue.io.enqReady(0),
            1.U(2.W), 0.U(2.W)))
    val remainingCount = sourceCount - pushed
    val sourceDrained =
        sourceCount =/= 0.U && remainingCount === 0.U

    val requestSlotFree =
        !waitResponse || (realResponse && sourceDrained)
    val outputSlotFree = bufferCount === 0.U || sourceDrained
    val flushDelay = RegNext(io.flush, false.B)
    val startF0 = !f1Valid && requestSlotFree && outputSlotFree &&
        !discardResponse && !io.flush && !flushDelay

    // ---------------------------------------------------------------------
    // F1: registered translation plus synchronous prediction
    // ---------------------------------------------------------------------
    val slot0Taken =
        predictor.io.result(0).taken && !f1Exception
    val lineHasSecond = f1Pc(3, 2) =/= 3.U
    val allowSecond = lineHasSecond && !f1Uncached &&
        !slot0Taken && !f1Exception
    val slot1Taken =
        predictor.io.result(1).taken && allowSecond
    val requestCount =
        Mux(allowSecond, 2.U(2.W), 1.U(2.W))
    val predictedNext = Mux(slot0Taken,
        predictor.io.result(0).target,
        Mux(slot1Taken, predictor.io.result(1).target,
            f1Pc + Mux(allowSecond, 8.U, 4.U)))

    val syntheticRequest = f1Valid && f1Exception
    io.cache.valid := f1Valid && !f1Exception && !io.flush
    val safePa = Mux(f1Exception, Config.START_PC, f1Pa)
    io.cache.index := safePa(11, 4)
    io.cache.tag := safePa(31, 12)
    io.cache.offset := safePa(3, 2)
    io.cache.uncached := f1Uncached
    val requestFire =
        (io.cache.valid && io.cache.addrOk) || syntheticRequest

    // A branch redirect suppresses the real ICache request above, but keeping
    // it in the speculative-consume equation creates a long EX branch-target
    // / compare -> frontend flush -> RAS/GHR write-enable path.  Treat the
    // redirected F1 entry as speculatively consumed when the cache would have
    // accepted it.  The mispredict update is registered in the predictor and
    // repairs GHR/RAS from the carried snapshots on the following cycle;
    // flushDelay prevents a new F0 launch until that repair has completed.
    val predictorConsumeFire =
        f1Valid && !f1Exception && io.cache.addrOk
    predictor.io.consume(0) :=
        predictorConsumeFire && predictor.io.result(0).hit
    predictor.io.consume(1) :=
        predictorConsumeFire && allowSecond &&
        predictor.io.result(1).hit

    when(io.flush) {
        pc := io.flushTarget
        f1Valid := false.B
        waitResponse := false.B
        pendingSynthetic := false.B
        bufferCount := 0.U
        when(waitResponse && !pendingSynthetic && !io.cache.dataOk) {
            discardResponse := true.B
        }
    }.otherwise {
        when(startF0) {
            f1Valid := true.B
            f1Pc := pc
            f1Pa := f0Pa
            f1Uncached := f0Uncached
            f1Exception := f0Exception
            f1Ecode := f0Ecode
        }

        when(requestFire) {
            pc := predictedNext
            f1Valid := false.B
            waitResponse := true.B
            pendingSynthetic := syntheticRequest
            pendingPc := f1Pc
            pendingCount := requestCount
            pendingException := f1Exception
            pendingEcode := f1Ecode
            for (slot <- 0 until 2) {
                pendingTaken(slot) := (if (slot == 0) {
                    slot0Taken
                } else {
                    slot1Taken
                })
                pendingTarget(slot) := predictor.io.result(slot).target
                pendingHit(slot) := predictor.io.result(slot).hit
                pendingWay(slot) := predictor.io.result(slot).way
                pendingCall(slot) := predictor.io.result(slot).isCall
                pendingReturn(slot) := predictor.io.result(slot).isReturn
                pendingHistorySpeculated(slot) :=
                    predictor.io.consume(slot) &&
                    predictor.io.result(slot).isConditional
                pendingGhr(slot) :=
                    predictor.io.result(slot).ghrSnapshot
                pendingRasSp(slot) :=
                    predictor.io.result(slot).rasSpSnapshot
            }
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
                buffer(0) := Mux(
                    pushed === 1.U, responseEntries(1), responseEntries(0))
                buffer(1) := responseEntries(1)
                bufferCount := remainingCount
            }
        }
    }

    when(discardResponse && io.cache.dataOk) {
        discardResponse := false.B
    }
}
