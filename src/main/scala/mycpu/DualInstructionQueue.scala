package mycpu

import chisel3._
import chisel3.util._

/**
  * Eight-entry ordered instruction queue with up to two adjacent pushes and
  * two adjacent pops per cycle.  Slot 1 is never accepted without slot 0.
  *
  * The oldest instruction is always stored in physical slot zero.  Popping
  * compacts the remaining entries, following NOP-Core's CompressedFIFO
  * organization.  Keeping both dequeue payloads at fixed register locations
  * avoids putting an eight-way head-pointer mux in front of the decoder and
  * dual-issue policy.
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
    val count = RegInit(0.U(4.W))

    io.count := count
    io.deqValid(0) := count =/= 0.U
    io.deqValid(1) := count >= 2.U
    io.deqBits(0) := entries(0)
    io.deqBits(1) := entries(1)

    // Deliberately do not consume freshly-popped space in the same cycle.
    // This keeps full-queue read/write aliasing out of the critical path.
    io.enqReady(0) := count <= 7.U
    io.enqReady(1) := count <= 6.U

    val push0 = io.enqValid(0) && io.enqReady(0)
    val push1 = push0 && io.enqValid(1) && io.enqReady(1)
    val pushCount = Mux(push1, 2.U(2.W), Mux(push0, 1.U(2.W), 0.U(2.W)))
    val remainingCount = count - io.popCount

    // Compact only live payloads.  Payload registers are intentionally
    // unreset; count is the sole validity state.  A flush therefore only
    // resets count and does not gate every payload register write enable.
    when(io.popCount === 1.U) {
        for (slot <- 0 until 7) {
            when((slot + 1).U < count) {
                entries(slot) := entries(slot + 1)
            }
        }
    }.elsewhen(io.popCount === 2.U) {
        for (slot <- 0 until 6) {
            when((slot + 2).U < count) {
                entries(slot) := entries(slot + 2)
            }
        }
    }

    // Enqueues land immediately after the compacted live prefix.  Ready
    // still depends on the registered pre-pop count, so this does not
    // introduce a combinational ready path through the queue.
    when(push0) {
        for (slot <- 0 until 8) {
            when(remainingCount === slot.U) {
                entries(slot) := io.enqBits(0)
            }
        }
    }
    when(push1) {
        for (slot <- 0 until 8) {
            when(remainingCount + 1.U === slot.U) {
                entries(slot) := io.enqBits(1)
            }
        }
    }

    when(io.flush) {
        count := 0.U
    }.otherwise {
        count := remainingCount + pushCount
    }

    assert(!io.enqValid(1) || io.enqValid(0), "slot1 enqueue requires slot0")
    assert(io.popCount <= 2.U, "dual instruction queue pops at most two entries")
    assert(io.popCount <= count, "dual instruction queue underflow")
}

/**
  * Four-entry decoded-instruction buffer.
  *
  * This is intentionally separate from FetchQ: FetchQ may advance whenever
  * this buffer has registered capacity, while hazard/serialization checks
  * consume the fixed slot-zero/slot-one outputs on the following cycle.
  * Consequently an instruction bit can no longer travel through decode and
  * issue logic and return to FetchQ's compaction enables in one cycle.
  */
class DualDecodeQueue extends Module {
    private val depth = 4

    val io = IO(new Bundle {
        val flush = Input(Bool())

        val enqValid = Input(Vec(2, Bool()))
        val enqBits = Input(Vec(2, new DualLaneData()))
        val enqReady = Output(Vec(2, Bool()))

        val deqValid = Output(Vec(2, Bool()))
        val deqBits = Output(Vec(2, new DualLaneData()))
        val popCount = Input(UInt(2.W))

        val count = Output(UInt(3.W))
    })

    val entries = Reg(Vec(depth, new DualLaneData()))
    // Prefix-valid (thermometer) state keeps each slot's movement control
    // local.  A binary count made the MSB travel through subtract/compare
    // logic to every bit of slot 3 before reaching its CE.
    val valid = RegInit(VecInit(Seq.fill(depth)(false.B)))
    val count = PopCount(valid)

    io.count := count
    io.deqValid(0) := valid(0)
    io.deqValid(1) := valid(1)
    io.deqBits(0) := entries(0)
    io.deqBits(1) := entries(1)

    // As with the other pipeline queues, readiness is registered-state only.
    // Depth four allows a two-wide dequeue and enqueue to sustain every cycle
    // without borrowing freshly-popped capacity.
    io.enqReady(0) := !valid(depth - 1)
    io.enqReady(1) := !valid(depth - 2)

    val push0 = io.enqValid(0) && io.enqReady(0)
    val push1 = push0 && io.enqValid(1) && io.enqReady(1)
    val afterPop = Wire(Vec(depth, Bool()))
    for (slot <- 0 until depth) {
        val afterOne =
            if (slot + 1 < depth) valid(slot + 1) else false.B
        val afterTwo =
            if (slot + 2 < depth) valid(slot + 2) else false.B
        afterPop(slot) := MuxLookup(io.popCount, valid(slot))(Seq(
            1.U -> afterOne,
            2.U -> afterTwo
        ))
    }

    // The valid vector is always a live prefix.  Locate its first zero with
    // only adjacent valid bits, insert lane 0, then repeat for lane 1.
    val insert0 = Wire(Vec(depth, Bool()))
    val validAfter0 = Wire(Vec(depth, Bool()))
    val insert1 = Wire(Vec(depth, Bool()))
    for (slot <- 0 until depth) {
        val previousLive =
            if (slot == 0) true.B else afterPop(slot - 1)
        insert0(slot) := push0 && previousLive && !afterPop(slot)
        validAfter0(slot) := afterPop(slot) || insert0(slot)
    }
    for (slot <- 0 until depth) {
        val previousLive =
            if (slot == 0) true.B else validAfter0(slot - 1)
        insert1(slot) := push1 && previousLive && !validAfter0(slot)
    }

    when(io.popCount === 1.U) {
        for (slot <- 0 until depth - 1) {
            when(valid(slot + 1)) {
                entries(slot) := entries(slot + 1)
            }
        }
    }.elsewhen(io.popCount === 2.U) {
        for (slot <- 0 until depth - 2) {
            when(valid(slot + 2)) {
                entries(slot) := entries(slot + 2)
            }
        }
    }

    for (slot <- 0 until depth) {
        when(insert0(slot)) {
            entries(slot) := io.enqBits(0)
        }
        when(insert1(slot)) {
            entries(slot) := io.enqBits(1)
        }
    }

    when(io.flush) {
        valid := VecInit(Seq.fill(depth)(false.B))
    }.otherwise {
        for (slot <- 0 until depth) {
            valid(slot) := validAfter0(slot) || insert1(slot)
        }
    }

    assert(!io.enqValid(1) || io.enqValid(0),
        "decoded slot1 enqueue requires slot0")
    assert(io.popCount <= 2.U,
        "decoded instruction queue pops at most two entries")
    assert(io.popCount <= count,
        "decoded instruction queue underflow")
}
