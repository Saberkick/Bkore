package mycpu

import chisel3._
import chisel3.util._

class StoreBufferEntry extends Bundle {
    val address = UInt(32.W)
    val data = UInt(32.W)
    val mask = UInt(4.W)
    val uncached = Bool()
}

/**
  * Four-entry, commit-aware local store buffer.
  *
  * Stores enter after translation but cannot reach DCache until WB marks them
  * committed.  Payload and CAM wiring are entirely local to the LSU.
  */
class StoreBuffer extends Module {
    val io = IO(new Bundle {
        val flushUncommitted = Input(Bool())

        val enq = Flipped(Decoupled(new StoreBufferEntry()))
        val commit = Input(Bool())

        val drainValid = Output(Bool())
        val drainBits = Output(new StoreBufferEntry())
        val drainStart = Input(Bool())
        val drainDone = Input(Bool())

        val loadValid = Input(Bool())
        val loadAddress = Input(UInt(32.W))
        val forwardMask = Output(UInt(4.W))
        val forwardData = Output(UInt(32.W))

        val empty = Output(Bool())
        val full = Output(Bool())
        val count = Output(UInt(3.W))
        val committedCount = Output(UInt(3.W))
    })

    val payload = Reg(Vec(4, new StoreBufferEntry()))
    val head = RegInit(0.U(2.W))
    val tail = RegInit(0.U(2.W))
    val commitPtr = RegInit(0.U(2.W))
    val count = RegInit(0.U(3.W))
    val committed = RegInit(0.U(3.W))
    val drainInFlight = RegInit(false.B)

    io.enq.ready := count =/= 4.U
    val enqFire = io.enq.valid && io.enq.ready
    val commitFire = io.commit && committed =/= count
    val drainDone = io.drainDone && drainInFlight

    io.drainValid := committed =/= 0.U && !drainInFlight
    io.drainBits := payload(head)
    io.empty := count === 0.U
    io.full := count === 4.U
    io.count := count
    io.committedCount := committed

    when(enqFire) {
        payload(tail) := io.enq.bits
    }

    when(io.flushUncommitted) {
        // Keep only the committed prefix.  A simultaneous completed drain
        // removes its already-committed head as usual.
        count := committed - drainDone.asUInt
        committed := committed - drainDone.asUInt
        tail := commitPtr
        when(drainDone) {
            head := head + 1.U
        }
    }.otherwise {
        count := count + enqFire.asUInt - drainDone.asUInt
        committed :=
            committed + commitFire.asUInt - drainDone.asUInt
        when(enqFire) {
            tail := tail + 1.U
        }
        when(commitFire) {
            commitPtr := commitPtr + 1.U
        }
        when(drainDone) {
            head := head + 1.U
        }
    }

    when(drainDone) {
        drainInFlight := false.B
    }.elsewhen(io.drainStart && io.drainValid) {
        drainInFlight := true.B
    }

    when(io.commit) {
        assert(committed =/= count,
            "WB attempted to commit a store absent from StoreBuffer")
    }
    when(io.drainStart) {
        assert(io.drainValid,
            "StoreBuffer drain started without a committed head")
    }

    // Search youngest to oldest.  Each byte selects its youngest matching
    // store independently, which handles overlapping byte/half/word stores.
    val selected = Wire(Vec(4, Bool()))
    val forwarded = Wire(Vec(4, UInt(8.W)))
    for (byte <- 0 until 4) {
        var byteSelected = false.B
        var byteData = 0.U(8.W)
        for (age <- 0 until 4) {
            val idx = (tail - 1.U - age.U)(1, 0)
            val entry = payload(idx)
            val candidate = io.loadValid && age.U < count &&
                entry.address(31, 2) === io.loadAddress(31, 2) &&
                entry.mask(byte)
            val take = candidate && !byteSelected
            byteData = Mux(take,
                entry.data(8 * byte + 7, 8 * byte), byteData)
            byteSelected = byteSelected || candidate
        }
        selected(byte) := byteSelected
        forwarded(byte) := byteData
    }
    io.forwardMask := selected.asUInt
    io.forwardData := forwarded.asUInt
}
