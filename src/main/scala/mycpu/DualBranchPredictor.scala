package mycpu

import chisel3._
import chisel3.util._

class DualBtbEntry extends Bundle {
    val tag           = UInt(22.W)
    val target        = UInt(32.W)
    val isConditional = Bool()
}

/**
  * Two-bank predictor for an aligned pair: 256 direct-mapped BTB entries
  * (two banks, 128 entries per bank) and 1024 two-bit gshare counters.
  *
  * Each fetch slot addresses a different bank.  Keeping one asynchronous read
  * per bank lets Vivado infer distributed memory instead of expanding a
  * multi-way Reg(Vec(...)) into tens of thousands of flops and muxes.
  */
class DualBranchPredictor extends Module {
    val io = IO(new Bundle {
        val reqPc  = Input(Vec(2, UInt(32.W)))
        val result = Output(Vec(2, new DualPredictResult()))
        val update = Input(new DualPredictorUpdate())
        val flushHistory = Input(Bool())
    })

    // Only validity metadata needs reset.  The entry memories have
    // asynchronous reads and synchronous writes, which maps naturally to
    // LUTRAM on 7-series devices.
    val btb = Seq.fill(2)(Mem(128, new DualBtbEntry()))
    val btbValid = RegInit(VecInit(Seq.fill(2)(
        VecInit(Seq.fill(128)(false.B))
    )))
    val pht = RegInit(VecInit(Seq.fill(2)(VecInit(Seq.fill(512)(1.U(2.W))))))
    val ghr = RegInit(0.U(8.W))

    // reqPc(0) and reqPc(1) are adjacent words, so they always use opposite
    // banks.  Select the matching request before the memories to avoid
    // duplicating both BTB read ports.
    val bankPc = Wire(Vec(2, UInt(32.W)))
    val bankEntry = Wire(Vec(2, new DualBtbEntry()))
    for (bank <- 0 until 2) {
        bankPc(bank) := Mux(io.reqPc(0)(2) === bank.U, io.reqPc(0), io.reqPc(1))
        bankEntry(bank) := btb(bank).read(bankPc(bank)(9, 3))
    }

    for (slot <- 0 until 2) {
        val pc = io.reqPc(slot)
        val bank = pc(2)
        val set = pc(9, 3)
        val tag = pc(31, 10)
        val phtIndex = pc(11, 3) ^ Cat(0.U(1.W), ghr)
        val entry = Mux(bank, bankEntry(1), bankEntry(0))
        val hit = btbValid(bank)(set) && entry.tag === tag
        val direction = pht(bank)(phtIndex)(1)

        io.result(slot).hit := hit
        io.result(slot).taken := hit && (!entry.isConditional || direction)
        io.result(slot).target := Mux(hit, entry.target, 0.U)
    }

    // Predictor training is deliberately one cycle behind branch resolution.
    // It has no architectural effect, and removes the direct EX-to-frontend
    // path that previously drove every BTB/PHT write decoder in one cycle.
    val update = RegInit(0.U.asTypeOf(new DualPredictorUpdate()))
    update := io.update

    when(io.flushHistory) {
        ghr := 0.U
    }.elsewhen(update.valid && update.isBranch && update.isConditional) {
        ghr := Cat(ghr(6, 0), update.taken)
    }

    when(update.valid) {
        val pc = update.pc
        val bank = pc(2)
        val set = pc(9, 3)
        val tag = pc(31, 10)
        val phtIndex = pc(11, 3) ^ Cat(0.U(1.W), ghr)

        when(update.isBranch) {
            val newEntry = Wire(new DualBtbEntry())
            newEntry.tag := tag
            newEntry.target := update.target
            newEntry.isConditional := update.isConditional
            when(bank === 0.U) {
                btb(0).write(set, newEntry)
            }.otherwise {
                btb(1).write(set, newEntry)
            }
            btbValid(bank)(set) := true.B
        }.otherwise {
            // A false BTB prediction may alias another entry at this index.
            // Clearing it is always architecturally safe; only prediction
            // quality can be affected until that branch is observed again.
            btbValid(bank)(set) := false.B
        }

        when(update.isBranch && update.isConditional) {
            val old = pht(bank)(phtIndex)
            when(update.taken) {
                pht(bank)(phtIndex) := Mux(old === 3.U, old, old + 1.U)
            }.otherwise {
                pht(bank)(phtIndex) := Mux(old === 0.U, old, old - 1.U)
            }
        }
    }

    assert(io.reqPc(0)(2) =/= io.reqPc(1)(2),
        "dual predictor requests must use different banks")
}
