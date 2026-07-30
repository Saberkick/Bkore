package mycpu

import chisel3._

class DualLaneData extends Bundle {
    val pipe = new PipelineData()
    val src1Read = Bool()
    val src2Read = Bool()
    val serializing = Bool()
    val waitDcache = Bool()
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
