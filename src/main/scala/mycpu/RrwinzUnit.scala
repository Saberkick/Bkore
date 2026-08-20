package mycpu

import chisel3._
import chisel3.util._

/**
  * Area-oriented implementation of the competition RRWINZ instruction.
  *
  * The instruction is rare and serialized by DualBackend, so iterating over
  * the two bit windows is preferable to putting a variable popcount, modulo
  * network and 32 independent barrel selectors on the normal EX timing path.
  * Windows extending beyond bit 31 are clipped; offset zero is a no-op.
  */
class RrwinzUnit extends Module {
    val io = IO(new Bundle {
        val enable = Input(Bool())
        val flush = Input(Bool())
        val consume = Input(Bool())
        val rk = Input(UInt(32.W))
        val rj = Input(UInt(32.W))
        val done = Output(Bool())
        val result = Output(UInt(32.W))
    })

    // 五态迭代流程：锁存请求、统计 1、求余、逐位旋转、保持结果。
    val sIdle :: sCal :: sDone :: Nil = Enum(3)
    val state = RegInit(sIdle)
    val rjReg = RegInit(0.U(32.W))
    val rkReg = RegInit(0.U(32.W))
    val resultBits = RegInit(VecInit(Seq.fill(32)(false.B)))

    // done 会一直保持到 consume，允许后级任意时长反压。
    io.done := state === sDone
    io.result := resultBits.asUInt

    when(io.flush) {
        state := sIdle
    }.otherwise {
        switch(state) {
            is(sIdle) {
                when(io.enable) {
                    // 请求只在 Idle 锁存一次；随后 EX 可持续保持 enable。
                    rjReg := io.rj
                    rkReg := io.rk
                    resultBits := VecInit(0.U(32.W))
                    rjWidthReg := inputRjWidth
                    rkWidthReg := inputRkWidth
                    state := sCal
                }
            }
            is(sCal) {
                // 每拍检查 rj 窗口中的一位，累加其中为 1 的位数。
                val isGET = RegInit(0.U(1.W))
                when (rjReg >= rkReg) {
                    resultBits := rjReg
                }.otherwise{
                    resultBits := rkReg
                }
                state := sDone
            }
            is(sDone) {
                // 等待后端确认 EX 前移后，才允许接收下一条 RRWINZ。
                when(io.consume) {
                    state := sIdle
                }
            }
        }
    }
}
