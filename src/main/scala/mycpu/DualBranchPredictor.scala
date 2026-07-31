package mycpu

import chisel3._
import chisel3.util._

class DualBtbEntry extends Bundle {
    val tag = UInt(22.W)
    val target = UInt(32.W)
    val isConditional = Bool()
    val isCall = Bool()
    val isReturn = Bool()
}

/**
  * Banked synchronous predictor.
  *
  * Payloads live in synchronous memories; only validity/LRU metadata resets.
  * The two adjacent fetch PCs always select opposite banks, so each bank needs
  * one prediction read port.  Training is registered and uses a separate
  * memory port rather than a backend-to-fetch combinational bypass.
  */
class DualBranchPredictor extends Module {
    val io = IO(new Bundle {
        val reqPc = Input(Vec(2, UInt(32.W)))
        val result = Output(Vec(2, new DualPredictResult()))
        val consume = Input(Vec(2, Bool()))
        val update = Input(new DualPredictorUpdate())
        val flushHistory = Input(Bool())
    })

    val btb = Seq.fill(2, 2)(SyncReadMem(128, new DualBtbEntry()))
    val pht = Seq.fill(2)(SyncReadMem(512, UInt(2.W)))

    val btbValid = RegInit(VecInit(Seq.fill(2)(
        VecInit(Seq.fill(2)(VecInit(Seq.fill(128)(false.B))))
    )))
    val lru = RegInit(VecInit(Seq.fill(2)(
        VecInit(Seq.fill(128)(false.B))
    )))
    val phtValid = RegInit(VecInit(Seq.fill(2)(
        VecInit(Seq.fill(512)(false.B))
    )))

    val ghr = RegInit(0.U(8.W))
    val ras = Seq.fill(8)(Reg(UInt(32.W)))
    val rasSp = RegInit(0.U(4.W))
    val rasTopIndex = Mux(
        rasSp === 0.U, 0.U(3.W), (rasSp - 1.U)(2, 0))
    val rasTopValue = ras.zipWithIndex.map { case (entry, index) =>
        Fill(32, rasTopIndex === index.U) & entry
    }.reduce(_ | _)
    val rasTop = Mux(
        rasSp === 0.U, 0.U(32.W), rasTopValue)

    // Route the two adjacent PCs to their natural banks.
    val bankPc = Wire(Vec(2, UInt(32.W)))
    bankPc(0) := Mux(io.reqPc(0)(2), io.reqPc(1), io.reqPc(0))
    bankPc(1) := Mux(io.reqPc(0)(2), io.reqPc(0), io.reqPc(1))
    val bankPhtIndex = Wire(Vec(2, UInt(9.W)))
    bankPhtIndex(0) := bankPc(0)(11, 3) ^ Cat(0.U(1.W), ghr)
    bankPhtIndex(1) := bankPc(1)(11, 3) ^ Cat(0.U(1.W), ghr)

    val btbRead = Wire(Vec(2, Vec(2, new DualBtbEntry())))
    for (bank <- 0 until 2; way <- 0 until 2) {
        btbRead(bank)(way) := btb(bank)(way).read(bankPc(bank)(9, 3))
    }
    val phtRead = Wire(Vec(2, UInt(2.W)))
    for (bank <- 0 until 2) {
        phtRead(bank) := pht(bank).read(bankPhtIndex(bank))
    }

    // Metadata below is aligned with the one-cycle synchronous RAM outputs.
    val reqPcReg = RegNext(io.reqPc)
    val reqBankReg = RegNext(VecInit(io.reqPc.map(_(2))))
    val reqSetReg = RegNext(VecInit(io.reqPc.map(_(9, 3))))
    val reqTagReg = RegNext(VecInit(io.reqPc.map(_(31, 10))))
    val reqPhtIndexReg = RegNext(VecInit(io.reqPc.map { pc =>
        pc(11, 3) ^ Cat(0.U(1.W), ghr)
    }))
    val reqGhrReg = RegNext(ghr)
    val reqRasSpReg = RegNext(rasSp)
    val reqRasTopReg = RegNext(rasTop)

    for (slot <- 0 until 2) {
        val bank = reqBankReg(slot)
        val set = reqSetReg(slot)
        val way0 = btbRead(bank)(0)
        val way1 = btbRead(bank)(1)
        val hit0 = btbValid(bank)(0)(set) && way0.tag === reqTagReg(slot)
        val hit1 = btbValid(bank)(1)(set) && way1.tag === reqTagReg(slot)
        val hit = hit0 || hit1
        val way = hit1
        val entry = Mux(way, way1, way0)
        val counterValid = phtValid(bank)(reqPhtIndexReg(slot))
        val direction = Mux(counterValid, phtRead(bank)(1), false.B)
        val predictedTarget = Mux(
            entry.isReturn && reqRasSpReg =/= 0.U,
            reqRasTopReg, entry.target)

        io.result(slot).hit := hit
        io.result(slot).taken :=
            hit && (!entry.isConditional || direction)
        io.result(slot).target := predictedTarget
        io.result(slot).way := way
        io.result(slot).isConditional := hit && entry.isConditional
        io.result(slot).isCall := hit && entry.isCall
        io.result(slot).isReturn := hit && entry.isReturn
        io.result(slot).ghrSnapshot := reqGhrReg
        io.result(slot).rasSpSnapshot := reqRasSpReg
    }

    // Training is intentionally registered.  Payload is unreset; updateValid
    // is the sole validity state.
    val updateReg = Reg(new DualPredictorUpdate())
    val updateValid = RegInit(false.B)
    when(io.flushHistory) {
        updateValid := false.B
    }.otherwise {
        updateValid := io.update.valid
        when(io.update.valid) {
            updateReg := io.update
        }
    }

    val updateBank = updateReg.pc(2)
    val updateSet = updateReg.pc(9, 3)
    val updateTag = updateReg.pc(31, 10)
    val updateVictim = Mux(
        updateReg.predictedHit &&
            btbValid(updateBank)(updateReg.predictedWay)(updateSet),
        updateReg.predictedWay,
        Mux(!btbValid(updateBank)(0)(updateSet), false.B,
            Mux(!btbValid(updateBank)(1)(updateSet), true.B,
                lru(updateBank)(updateSet))))
    val updateEntry = Wire(new DualBtbEntry())
    updateEntry.tag := updateTag
    updateEntry.target := updateReg.target
    updateEntry.isConditional := updateReg.isConditional
    updateEntry.isCall := updateReg.isCall
    updateEntry.isReturn := updateReg.isReturn

    when(updateValid) {
        when(updateReg.isBranch) {
            when(updateBank === 0.U) {
                when(updateVictim) {
                    btb(0)(1).write(updateSet, updateEntry)
                }.otherwise {
                    btb(0)(0).write(updateSet, updateEntry)
                }
            }.otherwise {
                when(updateVictim) {
                    btb(1)(1).write(updateSet, updateEntry)
                }.otherwise {
                    btb(1)(0).write(updateSet, updateEntry)
                }
            }
            btbValid(updateBank)(updateVictim)(updateSet) := true.B
            lru(updateBank)(updateSet) := !updateVictim
        }.elsewhen(updateReg.predictedHit) {
            btbValid(updateBank)(updateReg.predictedWay)(updateSet) := false.B
        }
    }

    // PHT training uses a synchronous read/modify/write pipeline.
    val trainRequest = updateValid && updateReg.isBranch &&
        updateReg.isConditional
    val trainIndex = updateReg.pc(11, 3) ^
        Cat(0.U(1.W), updateReg.ghrSnapshot)
    val trainOld0 = pht(0).read(trainIndex,
        trainRequest && updateBank === 0.U)
    val trainOld1 = pht(1).read(trainIndex,
        trainRequest && updateBank === 1.U)
    val trainValid = RegNext(trainRequest, false.B)
    val trainBankReg = RegEnable(updateBank, trainRequest)
    val trainIndexReg = RegEnable(trainIndex, trainRequest)
    val trainTakenReg = RegEnable(updateReg.taken, trainRequest)
    val trainWasValidReg = RegEnable(
        phtValid(updateBank)(trainIndex), trainRequest)
    val trainOld = Mux(trainBankReg, trainOld1, trainOld0)
    val trainNext = Mux(!trainWasValidReg,
        Mux(trainTakenReg, 2.U, 1.U),
        Mux(trainTakenReg,
            Mux(trainOld === 3.U, trainOld, trainOld + 1.U),
            Mux(trainOld === 0.U, trainOld, trainOld - 1.U)))

    when(trainValid) {
        when(trainBankReg === 0.U) {
            pht(0).write(trainIndexReg, trainNext)
        }.otherwise {
            pht(1).write(trainIndexReg, trainNext)
        }
        phtValid(trainBankReg)(trainIndexReg) := true.B
    }

    // Speculative history follows only predictions actually consumed by F1.
    val consumeConditional0 =
        io.consume(0) && io.result(0).isConditional
    val consumeConditional1 =
        io.consume(1) && io.result(1).isConditional
    val historyAfter0 = Mux(consumeConditional0,
        Cat(ghr(6, 0), io.result(0).taken), ghr)
    val historyAfter1 = Mux(consumeConditional1,
        Cat(historyAfter0(6, 0), io.result(1).taken), historyAfter0)

    val repairHistory = updateValid && updateReg.mispredict
    val unresolvedHistory = updateValid && updateReg.isBranch &&
        updateReg.isConditional && !updateReg.historySpeculated
    when(io.flushHistory) {
        ghr := 0.U
    }.elsewhen(repairHistory) {
        ghr := Mux(updateReg.isConditional,
            Cat(updateReg.ghrSnapshot(6, 0), updateReg.taken),
            updateReg.ghrSnapshot)
    }.elsewhen(unresolvedHistory) {
        ghr := Cat(ghr(6, 0), updateReg.taken)
    }.otherwise {
        ghr := historyAfter1
    }

    // RAS actions are similarly local.  Correctly predicted actions have
    // already been applied at consume time; a miss or misprediction repairs
    // from the snapshot carried with the fetch entry.
    val consumeCall0 = io.consume(0) && io.result(0).taken &&
        io.result(0).isCall
    val consumeReturn0 = io.consume(0) && io.result(0).taken &&
        io.result(0).isReturn
    val spAfter0 = Mux(consumeCall0, Mux(rasSp === 8.U, 8.U, rasSp + 1.U),
        Mux(consumeReturn0, Mux(rasSp === 0.U, 0.U, rasSp - 1.U), rasSp))
    val consumeCall1 = io.consume(1) && io.result(1).taken &&
        io.result(1).isCall
    val consumeReturn1 = io.consume(1) && io.result(1).taken &&
        io.result(1).isReturn
    val spAfter1 = Mux(consumeCall1,
        Mux(spAfter0 === 8.U, 8.U, spAfter0 + 1.U),
        Mux(consumeReturn1,
            Mux(spAfter0 === 0.U, 0.U, spAfter0 - 1.U), spAfter0))

    val predictedRasAction =
        (updateReg.isCall && updateReg.predictedCall) ||
        (updateReg.isReturn && updateReg.predictedReturn)
    val repairRas = updateValid &&
        (updateReg.mispredict ||
         ((updateReg.isCall || updateReg.isReturn) && !predictedRasAction))
    val repairBaseSp = Mux(updateReg.mispredict,
        updateReg.rasSpSnapshot, rasSp)
    val repairSp = Mux(updateReg.isCall,
        Mux(repairBaseSp === 8.U, 8.U, repairBaseSp + 1.U),
        Mux(updateReg.isReturn,
            Mux(repairBaseSp === 0.U, 0.U, repairBaseSp - 1.U),
            repairBaseSp))

    when(io.flushHistory) {
        rasSp := 0.U
    }.elsewhen(repairRas) {
        rasSp := repairSp
        when(updateReg.isCall) {
            val repairIndex = Mux(
                repairBaseSp === 8.U, 7.U(3.W), repairBaseSp(2, 0))
            for (index <- 0 until 8) {
                when(repairIndex === index.U) {
                    ras(index) := updateReg.pc + 4.U
                }
            }
        }
    }.otherwise {
        rasSp := spAfter1
        val call0Index = Mux(
            rasSp === 8.U, 7.U(3.W), rasSp(2, 0))
        val call1Index = Mux(
            spAfter0 === 8.U, 7.U(3.W), spAfter0(2, 0))
        for (index <- 0 until 8) {
            when(consumeCall0 && call0Index === index.U) {
                ras(index) := reqPcReg(0) + 4.U
            }
            when(consumeCall1 && call1Index === index.U) {
                ras(index) := reqPcReg(1) + 4.U
            }
        }
    }
}
