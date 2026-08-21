package mycpu

import chisel3._

class DualLaneData extends Bundle {
    val pipe = new PipelineData()
    val src1Read = Bool()
    val src2Read = Bool()
    // A simple consumer may enter the issue buffer one cycle before an older
    // M1b load returns.  The corresponding operand is filled from M2 before
    // the entry is allowed to leave the buffer.
    val src1LateLoad = Bool()
    val src2LateLoad = Bool()
    val serializing = Bool()
    val waitDcache = Bool()
    val dcacheDone = Bool()
    val dcacheBank = Bool()
}

class DualPacket extends Bundle {
    val valid = Vec(2, Bool())
    val lane  = Vec(2, new DualLaneData())
}

class DualCommitDebug extends Bundle {
    val pc = UInt(32.W)
    val wen = UInt(4.W)
    val wnum = UInt(5.W)
    val wdata = UInt(32.W)
}

/**
  * Static decode payload carried across the ID -> IS register boundary.
  *
  * Decode + register-file read happen in ID; `src1_value`/`src2_value` are the
  * architectural register values captured there (already including the WB
  * write bypass).  A producer that retires while this entry waits in the
  * buffer is re-forwarded by the backend's retired-write update, so these
  * captured values never go stale relative to IS.
  */
class DecodedLane extends Bundle {
    // IF generated
    val pc               = UInt(32.W)
    val inst             = UInt(32.W)
    val predictedHit     = Bool()
    val predictedTaken   = Bool()
    val predictedTarget  = UInt(32.W)
    val predictedHistory = UInt(6.W)

    // ID generated, consumed in EX
    val aluOp        = UInt(12.W)
    val mduOp        = UInt(7.W)
    val brType       = UInt(10.W)
    val imm          = UInt(32.W)
    val src1IsPC     = Bool()
    val src2IsImm    = Bool()
    val src2IsFour   = Bool()
    val src1_addr    = UInt(5.W)
    val src2_addr    = UInt(5.W)
    val src1_value   = UInt(32.W)
    val src2_value   = UInt(32.W)
    val resFromMulDiv = Bool()

    // consumed in MEM / WB
    val memWe       = Bool()
    val lsOp        = UInt(9.W)
    val resFromMem  = Bool()
    val regWriteEn  = Bool()
    val destReg     = UInt(5.W)

    // exception / privilege / CSR
    val hasException = Bool()
    val ecode        = UInt(6.W)
    val esubcode     = UInt(9.W)
    val isCsr        = Bool()
    val csrWe        = Bool()
    val csrNum       = UInt(14.W)
    val inst_ertn    = Bool()
    val rdtimel      = Bool()
    val rdtimeh      = Bool()
    val isCpucfg     = Bool()

    // TLB
    val tlbOp      = UInt(5.W)
    val invtlb_op  = UInt(5.W)
    val is_refetch = Bool()

    // cache
    val is_cacop  = Bool()
    val cacop_op  = UInt(5.W)
    val isLL      = Bool()
    val isSC      = Bool()

    // operand / issue metadata
    val src1Read    = Bool()
    val src2Read    = Bool()
    val serializing = Bool()
}

class DecodedPacket extends Bundle {
    val valid = Vec(2, Bool())
    val lane  = Vec(2, new DecodedLane())
}
