package mycpu

import chisel3._
import chisel3.util._

/**
  * Multi-cycle target generator for the custom BMIX branch.
  *
  *   shift  = popcount(rj XOR rk) mod 32
  *   mixed  = ror(rj + rk, shift)
  *   delta  = ZeroExtend(mixed[15:2] ## 00)
  *   target = pc + SignExtend(si16 ## 00) + delta
  *
  * Decoder supplies the already sign-extended and scaled SI16 through
  * `offset`.  Popcount and variable rotate are deliberately iterative so the
  * complex target calculation does not extend the ordinary ALU/branch path.
  */
class BmixTargetUnit extends Module {
    val io = IO(new Bundle {
        val enable = Input(Bool())
        val flush = Input(Bool())
        val consume = Input(Bool())
        val pc = Input(UInt(32.W))
        val rj = Input(UInt(32.W))
        val rk = Input(UInt(32.W))
        val offset = Input(UInt(32.W))
        val done = Output(Bool())
        val target = Output(UInt(32.W))
    })

    val sIdle :: sCount :: sRotate :: sFinish :: sDone :: Nil = Enum(5)
    val state = RegInit(sIdle)
    val xorReg = RegInit(0.U(32.W))
    val rotateReg = RegInit(0.U(32.W))
    val pcReg = RegInit(0.U(32.W))
    val offsetReg = RegInit(0.U(32.W))
    val bitIndex = RegInit(0.U(5.W))
    val bitCount = RegInit(0.U(6.W))
    val remaining = RegInit(0.U(5.W))
    val targetReg = RegInit(0.U(32.W))

    io.done := state === sDone
    io.target := targetReg

    when(io.flush) {
        state := sIdle
    }.otherwise {
        switch(state) {
            is(sIdle) {
                when(io.enable) {
                    xorReg := io.rj ^ io.rk
                    rotateReg := io.rj + io.rk
                    pcReg := io.pc
                    offsetReg := io.offset
                    bitIndex := 0.U
                    bitCount := 0.U
                    remaining := 0.U
                    state := sCount
                }
            }
            is(sCount) {
                // One source bit per cycle avoids a 32-input combinational
                // popcount tree on the normal EX timing path.
                val nextCount = bitCount + xorReg(bitIndex)
                bitCount := nextCount
                when(bitIndex === 31.U) {
                    // A popcount of 32 rotates by zero, hence low five bits.
                    remaining := nextCount(4, 0)
                    state := Mux(nextCount(4, 0) === 0.U, sFinish, sRotate)
                }.otherwise {
                    bitIndex := bitIndex + 1.U
                }
            }
            is(sRotate) {
                // One-bit rotate right: old bit 0 wraps into new bit 31.
                rotateReg := Cat(rotateReg(0), rotateReg(31, 1))
                when(remaining === 1.U) {
                    remaining := 0.U
                    state := sFinish
                }.otherwise {
                    remaining := remaining - 1.U
                }
            }
            is(sFinish) {
                // pc and the decoded SI16 offset are already word aligned.
                // Use only the aligned low 16 mixed bits as an extra delta.
                val mixedDelta = Cat(0.U(16.W), rotateReg(15, 2), 0.U(2.W))
                targetReg := pcReg + offsetReg + mixedDelta
                state := sDone
            }
            is(sDone) {
                // Preserve the target across arbitrary downstream backpressure.
                when(io.consume) {
                    state := sIdle
                }
            }
        }
    }
}
