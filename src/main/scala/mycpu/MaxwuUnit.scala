package mycpu

import chisel3._
import chisel3.util._

/**
  * Independent execution unit for the custom MAX.WU instruction.
  *
  * The control protocol deliberately follows RrwinzUnit: the request is
  * captured once in Idle, done is held until EX consumes the result, and a
  * pipeline flush discards all in-flight state.  Only the operation itself is
  * replaced with an unsigned maximum of rj and rk.
  */
class MaxwuUnit extends Module {
    val io = IO(new Bundle {
        val enable = Input(Bool())
        val flush = Input(Bool())
        val consume = Input(Bool())
        val rj = Input(UInt(32.W))
        val rk = Input(UInt(32.W))
        val done = Output(Bool())
        val result = Output(UInt(32.W))
    })

    val sIdle :: sCalculate :: sDone :: Nil = Enum(3)
    val state = RegInit(sIdle)
    val rjReg = RegInit(0.U(32.W))
    val rkReg = RegInit(0.U(32.W))
    val resultReg = RegInit(0.U(32.W))

    io.done := state === sDone
    io.result := resultReg

    when(io.flush) {
        state := sIdle
        rjReg := 0.U
        rkReg := 0.U
        resultReg := 0.U
    }.otherwise {
        switch(state) {
            is(sIdle) {
                when(io.enable) {
                    rjReg := io.rj
                    rkReg := io.rk
                    state := sCalculate
                }
            }
            is(sCalculate) {
                // UInt comparison is unsigned.  Equality selects rj; the
                // architectural value is identical whichever source wins.
                resultReg := Mux(rjReg >= rkReg, rjReg, rkReg)
                state := sDone
            }
            is(sDone) {
                when(io.consume) {
                    state := sIdle
                }
            }
        }
    }
}
