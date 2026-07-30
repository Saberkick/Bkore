package mycpu

import chisel3._
import chisel3.util._

class DualBtbEntry extends Bundle {
    val tag           = UInt(22.W)
    val target        = UInt(32.W)
    val isConditional = Bool()
}

/**
  * Two-bank predictor for an aligned pair: 512 total BTB entries (two banks,
  * 128 sets, two ways) and 1024 total two-bit gshare counters.
  */
class DualBranchPredictor extends Module {
    val io = IO(new Bundle {
        val reqPc  = Input(Vec(2, UInt(32.W)))
        val result = Output(Vec(2, new DualPredictResult()))
        val update = Input(new DualPredictorUpdate())
        val flushHistory = Input(Bool())
    })

    // Reset only validity metadata.  Targets/tags are don't-care while the
    // corresponding valid bit is clear, avoiding thousands of resettable
    // datapath flops in the generated FPGA netlist.
    val btb = Reg(Vec(2, Vec(2, Vec(128, new DualBtbEntry()))))
    val btbValid = RegInit(VecInit(Seq.fill(2)(
        VecInit(Seq.fill(2)(VecInit(Seq.fill(128)(false.B))))
    )))
    val lru = RegInit(VecInit(Seq.fill(2)(VecInit(Seq.fill(128)(false.B)))))
    val pht = RegInit(VecInit(Seq.fill(2)(VecInit(Seq.fill(512)(1.U(2.W))))))
    val ghr = RegInit(0.U(8.W))

    for (slot <- 0 until 2) {
        val pc = io.reqPc(slot)
        val bank = pc(2)
        val set = pc(9, 3)
        val tag = pc(31, 10)
        val phtIndex = pc(11, 3) ^ Cat(0.U(1.W), ghr)
        val way0 = btb(bank)(0)(set)
        val way1 = btb(bank)(1)(set)
        val hit0 = btbValid(bank)(0)(set) && way0.tag === tag
        val hit1 = btbValid(bank)(1)(set) && way1.tag === tag
        val hit = hit0 || hit1
        val entry = Mux(hit1, way1, way0)
        val direction = pht(bank)(phtIndex)(1)

        io.result(slot).hit := hit
        io.result(slot).taken := hit && (!entry.isConditional || direction)
        io.result(slot).target := entry.target
    }

    when(io.flushHistory) {
        ghr := 0.U
    }.elsewhen(io.update.valid) {
        val pc = io.update.pc
        val bank = pc(2)
        val set = pc(9, 3)
        val tag = pc(31, 10)
        val phtIndex = pc(11, 3) ^ Cat(0.U(1.W), ghr)
        val hit0 = btbValid(bank)(0)(set) && btb(bank)(0)(set).tag === tag
        val hit1 = btbValid(bank)(1)(set) && btb(bank)(1)(set).tag === tag
        val victim = Mux(hit0, false.B,
            Mux(hit1, true.B,
                Mux(!btbValid(bank)(0)(set), false.B,
                    Mux(!btbValid(bank)(1)(set), true.B, lru(bank)(set)))))

        when(io.update.isBranch) {
            btbValid(bank)(victim)(set) := true.B
            btb(bank)(victim)(set).tag := tag
            btb(bank)(victim)(set).target := io.update.target
            btb(bank)(victim)(set).isConditional := io.update.isConditional
            lru(bank)(set) := !victim
        }.otherwise {
            when(hit0) { btbValid(bank)(0)(set) := false.B }
            when(hit1) { btbValid(bank)(1)(set) := false.B }
        }

        when(io.update.isBranch && io.update.isConditional) {
            val old = pht(bank)(phtIndex)
            when(io.update.taken) {
                pht(bank)(phtIndex) := Mux(old === 3.U, old, old + 1.U)
            }.otherwise {
                pht(bank)(phtIndex) := Mux(old === 0.U, old, old - 1.U)
            }
            ghr := Cat(ghr(6, 0), io.update.taken)
        }
    }
}
