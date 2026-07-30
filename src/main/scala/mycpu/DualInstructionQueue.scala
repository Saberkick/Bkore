package mycpu

import chisel3._
import chisel3.util._

/**
  * Eight-entry ordered instruction queue with up to two adjacent pushes and
  * two adjacent pops per cycle.  Slot 1 is never accepted without slot 0.
  */
class DualInstructionQueue extends Module {
    val io = IO(new Bundle {
        val flush = Input(Bool())

        val enqValid = Input(Vec(2, Bool()))
        val enqBits  = Input(Vec(2, new DualFetchEntry()))
        val enqReady = Output(Vec(2, Bool()))

        val deqValid = Output(Vec(2, Bool()))
        val deqBits  = Output(Vec(2, new DualFetchEntry()))
        val popCount = Input(UInt(2.W))

        val count = Output(UInt(4.W))
    })

    val entries = Reg(Vec(8, new DualFetchEntry()))
    val head    = RegInit(0.U(3.W))
    val tail    = RegInit(0.U(3.W))
    val count   = RegInit(0.U(4.W))

    io.count := count
    io.deqValid(0) := count =/= 0.U
    io.deqValid(1) := count >= 2.U
    io.deqBits(0) := entries(head)
    io.deqBits(1) := MuxLookup(head, entries(0))(Seq(
        0.U -> entries(1),
        1.U -> entries(2),
        2.U -> entries(3),
        3.U -> entries(4),
        4.U -> entries(5),
        5.U -> entries(6),
        6.U -> entries(7),
        7.U -> entries(0)
    ))

    // Deliberately do not consume freshly-popped space in the same cycle.
    // This keeps full-queue read/write aliasing out of the critical path.
    io.enqReady(0) := count <= 7.U
    io.enqReady(1) := count <= 6.U

    val push0 = io.enqValid(0) && io.enqReady(0)
    val push1 = push0 && io.enqValid(1) && io.enqReady(1)
    val pushCount = Mux(push1, 2.U(2.W), Mux(push0, 1.U(2.W), 0.U(2.W)))
    val legalPopCount = Mux(io.popCount > count, count(1, 0), io.popCount)

    when(io.flush) {
        head  := 0.U
        tail  := 0.U
        count := 0.U
    }.otherwise {
        when(push0) {
            entries(tail) := io.enqBits(0)
        }
        when(push1) {
            val tailPlusOne = Mux(tail === 7.U, 0.U(3.W), tail + 1.U)
            entries(tailPlusOne) := io.enqBits(1)
        }

        head  := (head + legalPopCount)(2, 0)
        tail  := (tail + pushCount)(2, 0)
        count := count + pushCount - legalPopCount
    }

    assert(!io.enqValid(1) || io.enqValid(0), "slot1 enqueue requires slot0")
    assert(io.popCount <= 2.U, "dual instruction queue pops at most two entries")
    assert(io.popCount <= count, "dual instruction queue underflow")
}
