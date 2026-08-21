package mycpu

import chisel3._
import chisel3.util._

/**
  * Area-oriented implementation of the competition RRIWINZ instruction.
  *
  * Decoder has already sign-extended SI11 to 32 bits before it reaches this
  * unit.  The four leading-zero counts are produced in parallel, then the
  * maximum (0..16) is consumed by a one-bit-per-cycle rotate-right loop.  This
  * keeps a variable 32-bit barrel rotator off the ordinary ALU timing path.
  */
class RriwinzUnit extends Module {
    val io = IO(new Bundle {
        val enable = Input(Bool())
        val flush = Input(Bool())
        val consume = Input(Bool())
        val rj = Input(UInt(32.W))
        val rk = Input(UInt(32.W))
        // SignExtend(si11, 32), not the raw eleven encoded bits.
        val imm = Input(UInt(32.W))
        val done = Output(Bool())
        val result = Output(UInt(32.W))
    })

    /** Count leading zeroes in a 16-bit halfword; zero maps to 16. */
    private def nlz16(value: UInt): UInt = {
        MuxCase(16.U(5.W), (15 to 0 by -1).map { bit =>
            value(bit) -> (15 - bit).U(5.W)
        })
    }

    val shift0 = nlz16(io.rj(31, 16))
    val shift1 = nlz16(io.rj(15, 0))
    val shift2 = nlz16(io.rk(31, 16))
    val shift3 = nlz16(io.rk(15, 0))
    val max01 = Mux(shift0 >= shift1, shift0, shift1)
    val max23 = Mux(shift2 >= shift3, shift2, shift3)
    val requestedShift = Mux(max01 >= max23, max01, max23)

    val sIdle :: sRotate :: sDone :: Nil = Enum(3)
    val state = RegInit(sIdle)
    val rotateReg = RegInit(0.U(32.W))
    val remaining = RegInit(0.U(5.W))

    io.done := state === sDone
    io.result := rotateReg

    when(io.flush) {
        state := sIdle
        remaining := 0.U
    }.otherwise {
        switch(state) {
            is(sIdle) {
                when(io.enable) {
                    // io.imm already contains SignExtend(si11, 32).  A zero
                    // shift is a no-op and may transition directly to done.
                    rotateReg := io.imm
                    remaining := requestedShift
                    state := Mux(requestedShift === 0.U, sDone, sRotate)
                }
            }
            is(sRotate) {
                // ror(x, 1): old bit 0 wraps around into new bit 31.
                val nextRotate = Cat(rotateReg(0), rotateReg(31, 1))
                rotateReg := nextRotate
                when(remaining === 1.U) {
                    remaining := 0.U
                    state := sDone
                }.otherwise {
                    remaining := remaining - 1.U
                }
            }
            is(sDone) {
                // Hold the complete result until EX is actually allowed to
                // advance, so downstream backpressure cannot lose it.
                when(io.consume) {
                    state := sIdle
                }
            }
        }
    }
}
