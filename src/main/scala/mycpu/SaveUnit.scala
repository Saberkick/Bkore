package mycpu

import chisel3._
import chisel3.util._

/**
  * Dedicated multi-cycle implementation of the custom SAVE instruction.
  *
  * SAVE means "state avalanche" here.  It performs eight dependent mixing
  * rounds, which intentionally cannot be represented as one ordinary ALU op:
  *
  *   state(0)   = rj XOR rk
  *   sum(i)     = state(i) + 0x9e3779b9 + i
  *   state(i+1) = rol(sum(i), 5) XOR (state(i) >> 7) XOR rk
  *   rd         = state(8)
  *
  * The result remains asserted until consume, and flush discards all
  * speculative state so the unit obeys the backend's precise-control rules.
  */
class SaveUnit extends Module {
    val io = IO(new Bundle {
        val enable = Input(Bool())
        val flush = Input(Bool())
        val consume = Input(Bool())
        val rj = Input(UInt(32.W))
        val rk = Input(UInt(32.W))
        val done = Output(Bool())
        val result = Output(UInt(32.W))
    })

    val sIdle :: sRound :: sDone :: Nil = Enum(3)
    val state = RegInit(sIdle)
    val stateReg = RegInit(0.U(32.W))
    val rkReg = RegInit(0.U(32.W))
    val roundReg = RegInit(0.U(3.W))

    io.done := state === sDone
    io.result := stateReg

    when(io.flush) {
        state := sIdle
    }.otherwise {
        switch(state) {
            is(sIdle) {
                when(io.enable) {
                    stateReg := io.rj ^ io.rk
                    rkReg := io.rk
                    roundReg := 0.U
                    state := sRound
                }
            }
            is(sRound) {
                val sum = stateReg + "h9e3779b9".U + roundReg
                val rotateLeftFive = Cat(sum(26, 0), sum(31, 27))
                val nextState = rotateLeftFive ^ (stateReg >> 7) ^ rkReg
                stateReg := nextState
                when(roundReg === 7.U) {
                    state := sDone
                }.otherwise {
                    roundReg := roundReg + 1.U
                }
            }
            is(sDone) {
                when(io.consume) {
                    state := sIdle
                }
            }
        }
    }
}
