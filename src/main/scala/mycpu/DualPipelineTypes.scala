package mycpu

import chisel3._
import chisel3.util._

class DualLaneData extends Bundle {
    val pipe = new PipelineData()
    val src1Read = Bool()
    val src2Read = Bool()
    val serializing = Bool()
    val waitDcache = Bool()
    val loadForwardMask = UInt(4.W)
    val loadForwardData = UInt(32.W)
}

class DualPacket extends Bundle {
    val epoch = UInt(2.W)
    val valid = Vec(2, Bool())
    val lane  = Vec(2, new DualLaneData())
}

/** Payload retained after EX has consumed decode/prediction controls. */
class PostExPipelineData extends Bundle {
    val pc = UInt(32.W)
    val inst = UInt(32.W)
    val mduOp = UInt(7.W)
    val src1_value = UInt(32.W)
    val src2_value = UInt(32.W)
    val resFromMulDiv = Bool()
    val memWe = Bool()
    val lsOp = UInt(8.W)
    val resFromMem = Bool()
    val regWriteEn = Bool()
    val destReg = UInt(5.W)
    val ex_result = UInt(32.W)
    val aux_data = UInt(32.W)
    val hasException = Bool()
    val ecode = UInt(6.W)
    val esubcode = UInt(9.W)
    val isCsr = Bool()
    val csrWe = Bool()
    val csrNum = UInt(14.W)
    val inst_ertn = Bool()
    val tlbOp = UInt(5.W)
    val invtlb_op = UInt(5.W)
    val is_refetch = Bool()
    val is_cacop = Bool()
    val cacop_op = UInt(5.W)
    val isLL = Bool()
    val isSC = Bool()
}

class PostExLaneData extends Bundle {
    val pipe = new PostExPipelineData()
    val serializing = Bool()
    val waitDcache = Bool()
    val loadForwardMask = UInt(4.W)
    val loadForwardData = UInt(32.W)
}

class PostExPacket extends Bundle {
    val epoch = UInt(2.W)
    val valid = Vec(2, Bool())
    val lane = Vec(2, new PostExLaneData())
}

/** Payload retained from registered M1 translation through M2. */
class M2PipelineData extends Bundle {
    val pc = UInt(32.W)
    val inst = UInt(32.W)
    val src1_value = UInt(32.W)
    val src2_value = UInt(32.W)
    val memWe = Bool()
    val lsOp = UInt(8.W)
    val resFromMem = Bool()
    val regWriteEn = Bool()
    val destReg = UInt(5.W)
    val ex_result = UInt(32.W)
    val aux_data = UInt(32.W)
    val hasException = Bool()
    val ecode = UInt(6.W)
    val esubcode = UInt(9.W)
    val isCsr = Bool()
    val csrWe = Bool()
    val csrNum = UInt(14.W)
    val inst_ertn = Bool()
    val tlbOp = UInt(5.W)
    val invtlb_op = UInt(5.W)
    val is_refetch = Bool()
    val isLL = Bool()
    val isSC = Bool()
}

class M2LaneData extends Bundle {
    val pipe = new M2PipelineData()
    val serializing = Bool()
    val waitDcache = Bool()
    val loadForwardMask = UInt(4.W)
    val loadForwardData = UInt(32.W)
}

class M2Packet extends Bundle {
    val epoch = UInt(2.W)
    val valid = Vec(2, Bool())
    val lane = Vec(2, new M2LaneData())
}

/** Minimal retirement payload; load extraction and LSU metadata are gone. */
class WbPipelineData extends Bundle {
    val pc = UInt(32.W)
    val inst = UInt(32.W)
    val src1_value = UInt(32.W)
    val src2_value = UInt(32.W)
    val memWe = Bool()
    val regWriteEn = Bool()
    val destReg = UInt(5.W)
    val ex_result = UInt(32.W)
    val aux_data = UInt(32.W)
    val hasException = Bool()
    val ecode = UInt(6.W)
    val esubcode = UInt(9.W)
    val isCsr = Bool()
    val csrWe = Bool()
    val csrNum = UInt(14.W)
    val inst_ertn = Bool()
    val tlbOp = UInt(5.W)
    val invtlb_op = UInt(5.W)
    val is_refetch = Bool()
    val isLL = Bool()
    val isSC = Bool()
}

class WbLaneData extends Bundle {
    val pipe = new WbPipelineData()
    val serializing = Bool()
}

class WbPacket extends Bundle {
    val epoch = UInt(2.W)
    val valid = Vec(2, Bool())
    val lane = Vec(2, new WbLaneData())
}

/**
  * A deliberately non-flowing, compacting local queue.
  *
  * `enq.ready` depends only on the registered local count.  In particular it
  * does not borrow space from a same-cycle dequeue, so no downstream ready
  * signal can propagate through this boundary.  The oldest payload is always
  * physical slot zero; dequeue compacts later slots toward zero, following
  * NOP-Core's CompressedFIFO organization.  This avoids putting a dynamic
  * head-pointer mux in front of every bit of the EX/M1 packets and therefore
  * keeps TLB/cache address paths behind a real register boundary.
  *
  * Payload registers are intentionally left unreset; reset/flush only touches
  * the small control state.
  */
class LocalQueue[T <: Data](gen: T, depth: Int) extends Module {
    require(depth >= 2)

    private val countWidth = log2Ceil(depth + 1)

    val io = IO(new Bundle {
        val flush = Input(Bool())
        val enq = Flipped(Decoupled(gen))
        val deq = Decoupled(gen)
        val count = Output(UInt(countWidth.W))
        // Expose every physical slot to local control logic.  Consumers must
        // qualify slotBits with slotValid because payload registers are left
        // unreset and therefore contain don't-care data outside the count.
        // The dequeue interface remains the only data-transfer interface.
        val slotValid = Output(Vec(depth, Bool()))
        val slotBits = Output(Vec(depth, gen))
    })

    val payload = Reg(Vec(depth, gen))
    val count = RegInit(0.U(countWidth.W))

    io.enq.ready := count =/= depth.U
    io.deq.valid := count =/= 0.U
    io.deq.bits := payload(0)
    io.count := count
    io.slotBits := payload
    for (slot <- 0 until depth) {
        io.slotValid(slot) := slot.U < count
    }

    val enqFire = io.enq.valid && io.enq.ready
    val deqFire = io.deq.valid && io.deq.ready

    // Payload contents are irrelevant while count is zero.  Let payload
    // compaction/writes proceed during a flush and give the flush priority
    // only on the narrow count state.  This keeps a global retirement flush
    // out of every wide queue payload CE/D cone.
    when(deqFire) {
        for (slot <- 0 until depth - 1) {
            when((slot + 1).U < count) {
                payload(slot) := payload(slot + 1)
            }
        }
    }
    when(enqFire) {
        val insertionIndex =
            Mux(deqFire, count - 1.U, count)
        for (slot <- 0 until depth) {
            when(insertionIndex === slot.U) {
                payload(slot) := io.enq.bits
            }
        }
    }

    when(io.flush) {
        count := 0.U
    }.otherwise {
        count := count + enqFire.asUInt - deqFire.asUInt
    }
}

class MulResultEntry extends Bundle {
    val epoch = UInt(2.W)
    val data = UInt(32.W)
}

class DcacheResultEntry extends Bundle {
    val epoch = UInt(2.W)
    val data = UInt(32.W)
}

class DualCommitDebug extends Bundle {
    val pc = UInt(32.W)
    val wen = UInt(4.W)
    val wnum = UInt(5.W)
    val wdata = UInt(32.W)
}
