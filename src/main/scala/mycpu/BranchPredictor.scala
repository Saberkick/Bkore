package mycpu

import chisel3._
import chisel3.util._

/** A small direct-mapped BTB with a local two-bit direction counter per entry.
  *
  * The valid vector is reset, while tags/targets/counters are only read after a
  * valid hit.  This keeps reset off the predictor payload registers.
  */
class BranchPredictor(entries: Int = 32) extends Module {
    require(entries >= 2 && isPow2(entries), "BTB entry count must be a power of two")

    private val indexWidth = log2Ceil(entries)
    private val tagWidth   = 30 - indexWidth // PC[1:0] is not stored

    val io = IO(new Bundle {
        val lookupPc       = Input(UInt(32.W))
        val predictedTaken = Output(Bool())
        val predictedTarget = Output(UInt(32.W))
        val update         = Input(new BranchPredictorUpdate())
    })

    val valid   = RegInit(VecInit(Seq.fill(entries)(false.B)))
    val tags    = Reg(Vec(entries, UInt(tagWidth.W)))
    val targets = Reg(Vec(entries, UInt(32.W)))
    val counters = Reg(Vec(entries, UInt(2.W)))

    private def indexOf(pc: UInt): UInt = pc(indexWidth + 1, 2)
    private def tagOf(pc: UInt): UInt = pc(31, indexWidth + 2)

    val lookupIndex = indexOf(io.lookupPc)
    val lookupHit = valid(lookupIndex) && tags(lookupIndex) === tagOf(io.lookupPc)

    io.predictedTaken  := lookupHit && counters(lookupIndex)(1)
    io.predictedTarget := Mux(lookupHit, targets(lookupIndex), io.lookupPc + 4.U)

    val updateIndex = indexOf(io.update.pc)
    val updateHit = valid(updateIndex) && tags(updateIndex) === tagOf(io.update.pc)
    val oldCounter = counters(updateIndex)

    when(io.update.valid) {
        when(!io.update.isBranch) {
            // A stale BTB hit can only occur after code at this PC changed.
            valid(updateIndex) := false.B
        } .otherwise {
            valid(updateIndex)   := true.B
            tags(updateIndex)    := tagOf(io.update.pc)
            targets(updateIndex) := io.update.target

            when(!updateHit) {
                // Allocate toward the observed outcome: weakly taken/not-taken.
                counters(updateIndex) := Mux(io.update.taken, "b10".U, "b01".U)
            } .elsewhen(io.update.taken && oldCounter =/= "b11".U) {
                counters(updateIndex) := oldCounter + 1.U
            } .elsewhen(!io.update.taken && oldCounter =/= "b00".U) {
                counters(updateIndex) := oldCounter - 1.U
            }
        }
    }
}
