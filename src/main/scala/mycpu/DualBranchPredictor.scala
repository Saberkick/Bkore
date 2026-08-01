package mycpu

import chisel3._
import chisel3.util._

class DualBtbEntry extends Bundle {
    val tag           = UInt(20.W)
    val target        = UInt(32.W)
    val isConditional = Bool()
    val isCall        = Bool()
    val isReturn      = Bool()
}

/**
  * Two-cycle, two-bank local-history branch predictor.
  *
  * F1 presents two adjacent PCs.  BHT and BTB are synchronous-read memories,
  * so the prediction is returned in F2.  PC bit 2 chooses the bank and bits
  * 11:3 choose one of 512 entries in that bank: 1024 entries in total without
  * duplicating the complete table for the two fetch slots.
  */
class DualBranchPredictor(clearSets: Int = 512) extends Module {
    val io = IO(new Bundle {
        val reqValid = Input(Bool())
        val reqPc    = Input(Vec(2, UInt(32.W)))
        val resultValid = Output(Bool())
        val result   = Output(Vec(2, new DualPredictResult()))

        // A consumed prediction is the control-flow instruction selected by
        // the frontend for the next PC.  It is the only event that may update
        // the speculative RAS.
        val consume = Input(Bool())
        val consumeSlot = Input(UInt(1.W))

        val update = Input(new DualPredictorUpdate())
        val rasCommit = Input(new DualRasCommit())
        val flush = Input(Bool())
    })

    private val Banks = 2
    private val SetsPerBank = 512
    private val HistoryBits = 6
    private val RasDepth = 8
    require(clearSets >= 1 && clearSets <= SetsPerBank)

    val bht = Seq.fill(Banks)(SyncReadMem(SetsPerBank, UInt(HistoryBits.W)))
    val btb = Seq.fill(Banks)(SyncReadMem(SetsPerBank, new DualBtbEntry()))

    // Payload memories deliberately have no reset.  Validity uses compact
    // LUTRAM and is swept to zero after reset instead of expanding 1024 reset
    // bits and two 512:1 muxes into thousands of FF/LUT cells.
    val btbValid = Seq.fill(Banks)(Mem(SetsPerBank, Bool()))
    val clearActive = RegInit(true.B)
    val clearIndex = RegInit(0.U(9.W))
    when(clearActive) {
        when(clearIndex === (clearSets - 1).U) {
            clearActive := false.B
        }.otherwise {
            clearIndex := clearIndex + 1.U
        }
    }

    val validWriteEnable = WireDefault(VecInit(Seq.fill(Banks)(false.B)))
    val validWriteAddress = WireDefault(VecInit(Seq.fill(Banks)(0.U(9.W))))
    val validWriteData = WireDefault(VecInit(Seq.fill(Banks)(false.B)))
    when(clearActive) {
        for (bank <- 0 until Banks) {
            validWriteEnable(bank) := true.B
            validWriteAddress(bank) := clearIndex
            validWriteData(bank) := false.B
        }
    }
    val pht = RegInit(VecInit(Seq.fill(Banks)(
        VecInit(Seq.fill(1 << HistoryBits)(1.U(2.W))))))

    // The generated SyncReadMem model drives X whenever its registered read
    // enable is low.  F2 may have to hold a prediction for many cycles while
    // the blocking ICache is busy, so keep the RAM ports enabled and hold
    // their addresses on the last accepted request instead of relying on the
    // memory output to retain its value.
    val reqPcReg = RegInit(VecInit(Config.START_PC, 0x1c000004.U(32.W)))
    val readPc = Wire(Vec(2, UInt(32.W)))
    readPc := Mux(io.reqValid, io.reqPc, reqPcReg)
    val bankPc = Wire(Vec(Banks, UInt(32.W)))
    val bhtRead = Wire(Vec(Banks, UInt(HistoryBits.W)))
    val btbRead = Wire(Vec(Banks, new DualBtbEntry()))
    for (bank <- 0 until Banks) {
        bankPc(bank) := Mux(readPc(0)(2) === bank.U, readPc(0), readPc(1))
        bhtRead(bank) := bht(bank).read(bankPc(bank)(11, 3), true.B)
        btbRead(bank) := btb(bank).read(bankPc(bank)(11, 3), true.B)
    }

    // Keep both asynchronous-reset values as literals.  firtool rejects an
    // arithmetic expression on an async reset even when it is constant.
    when(io.reqValid) {
        reqPcReg := io.reqPc
    }
    val responseValid = RegNext(io.reqValid, false.B)
    io.resultValid := responseValid

    val speculativeRas = RegInit(VecInit(Seq.fill(RasDepth)(Config.START_PC)))
    val speculativeDepth = RegInit(0.U(4.W))
    val committedRas = RegInit(VecInit(Seq.fill(RasDepth)(Config.START_PC)))
    val committedDepth = RegInit(0.U(4.W))

    for (slot <- 0 until 2) {
        val pc = reqPcReg(slot)
        val bank = pc(2)
        val set = pc(11, 3)
        val entry = Mux(bank, btbRead(1), btbRead(0))
        val entryValid = !clearActive && Mux(bank,
            btbValid(1).read(set), btbValid(0).read(set))
        val history = Mux(entryValid && entry.isConditional,
            Mux(bank, bhtRead(1), bhtRead(0)), 0.U)
        val counter = Mux(bank, pht(1)(history), pht(0)(history))
        val tagHit = entryValid && entry.tag === pc(31, 12)
        // The synchronous memory addresses remain on reqPcReg while reqValid
        // is low, allowing F2 to hold a prediction across an ICache stall.
        val hit = tagHit
        val rasIndex = (speculativeDepth - 1.U)(2, 0)
        val rasAvailable = speculativeDepth =/= 0.U
        val predictedTarget = Mux(entry.isReturn && rasAvailable,
            speculativeRas(rasIndex), entry.target)

        io.result(slot).hit := hit
        io.result(slot).taken := hit && (!entry.isConditional || counter(1))
        io.result(slot).target := Mux(hit, predictedTarget, 0.U)
        io.result(slot).history := history
        io.result(slot).isCall := hit && entry.isCall
        io.result(slot).isReturn := hit && entry.isReturn
    }

    // Predictor training is intentionally registered.  The history snapshot
    // carried by the instruction avoids a third BHT read port and guarantees
    // that PHT training uses the history which produced the prediction.
    val update = RegNext(io.update, 0.U.asTypeOf(new DualPredictorUpdate()))
    when(update.valid && !clearActive) {
        val bank = update.pc(2)
        val set = update.pc(11, 3)
        when(update.isBranch) {
            val newEntry = Wire(new DualBtbEntry())
            newEntry.tag := update.pc(31, 12)
            newEntry.target := update.target
            newEntry.isConditional := update.isConditional
            newEntry.isCall := update.isCall
            newEntry.isReturn := update.isReturn
            when(bank === 0.U) {
                btb(0).write(set, newEntry)
                validWriteEnable(0) := true.B
                validWriteAddress(0) := set
                validWriteData(0) := true.B
            }.otherwise {
                btb(1).write(set, newEntry)
                validWriteEnable(1) := true.B
                validWriteAddress(1) := set
                validWriteData(1) := true.B
            }
        }.elsewhen(update.predictedHit) {
            when(bank === 0.U) {
                validWriteEnable(0) := true.B
                validWriteAddress(0) := set
                validWriteData(0) := false.B
            }.otherwise {
                validWriteEnable(1) := true.B
                validWriteAddress(1) := set
                validWriteData(1) := false.B
            }
        }

        when(update.isBranch && update.isConditional) {
            val nextHistory = Cat(update.history(HistoryBits - 2, 0), update.taken)
            when(bank === 0.U) {
                bht(0).write(set, nextHistory)
            }.otherwise {
                bht(1).write(set, nextHistory)
            }

            val oldCounter = Mux(bank, pht(1)(update.history), pht(0)(update.history))
            val nextCounter = Mux(update.taken,
                Mux(oldCounter === 3.U, oldCounter, oldCounter + 1.U),
                Mux(oldCounter === 0.U, oldCounter, oldCounter - 1.U))
            when(bank === 0.U) {
                pht(0)(update.history) := nextCounter
            }.otherwise {
                pht(1)(update.history) := nextCounter
            }
        }
    }

    for (bank <- 0 until Banks) {
        when(validWriteEnable(bank)) {
            btbValid(bank).write(validWriteAddress(bank), validWriteData(bank))
        }
    }

    // The committed shadow supplies a deterministic recovery point.  The RAS
    // is a performance structure only; architectural state never depends on
    // it.  A full stack copies on flush because the depth is only eight.
    when(io.rasCommit.valid) {
        when(io.rasCommit.isCall) {
            when(committedDepth < RasDepth.U) {
                committedRas(committedDepth(2, 0)) := io.rasCommit.returnAddress
                committedDepth := committedDepth + 1.U
            }.otherwise {
                for (idx <- 0 until RasDepth - 1) {
                    committedRas(idx) := committedRas(idx + 1)
                }
                committedRas(RasDepth - 1) := io.rasCommit.returnAddress
            }
        }.elsewhen(io.rasCommit.isReturn && committedDepth =/= 0.U) {
            committedDepth := committedDepth - 1.U
        }
    }

    val selectedPrediction = Mux(io.consumeSlot.asBool, io.result(1), io.result(0))
    val selectedPc = Mux(io.consumeSlot.asBool, reqPcReg(1), reqPcReg(0))
    // io.flush is already registered at the backend/frontend boundary and is
    // aligned with the registered predictor update.
    when(io.flush) {
        for (idx <- 0 until RasDepth) {
            speculativeRas(idx) := committedRas(idx)
        }
        speculativeDepth := committedDepth

        // A CALL whose target prediction was wrong must already be visible to
        // a return fetched from the corrected target, before the CALL retires.
        when(update.valid && update.redirect && update.taken && update.isCall) {
            when(committedDepth < RasDepth.U) {
                speculativeRas(committedDepth(2, 0)) := update.pc + 4.U
                speculativeDepth := committedDepth + 1.U
            }.otherwise {
                for (idx <- 0 until RasDepth - 1) {
                    speculativeRas(idx) := committedRas(idx + 1)
                }
                speculativeRas(RasDepth - 1) := update.pc + 4.U
            }
        }.elsewhen(update.valid && update.redirect && update.taken &&
                update.isReturn && committedDepth =/= 0.U) {
            speculativeDepth := committedDepth - 1.U
        }
    }.elsewhen(io.consume && selectedPrediction.taken) {
        when(selectedPrediction.isCall) {
            when(speculativeDepth < RasDepth.U) {
                speculativeRas(speculativeDepth(2, 0)) := selectedPc + 4.U
                speculativeDepth := speculativeDepth + 1.U
            }.otherwise {
                for (idx <- 0 until RasDepth - 1) {
                    speculativeRas(idx) := speculativeRas(idx + 1)
                }
                speculativeRas(RasDepth - 1) := selectedPc + 4.U
            }
        }.elsewhen(selectedPrediction.isReturn && speculativeDepth =/= 0.U) {
            speculativeDepth := speculativeDepth - 1.U
        }
    }

    when(io.reqValid) {
        assert(io.reqPc(0)(2) =/= io.reqPc(1)(2),
            "dual predictor requests must use different banks")
    }
}
