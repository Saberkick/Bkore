package mycpu

import chisel3._

/** One architecturally ordered instruction in the dual-width frontend. */
class DualFetchEntry extends Bundle {
    val pc              = UInt(32.W)
    val inst            = UInt(32.W)
    val predictedTaken  = Bool()
    val predictedTarget = UInt(32.W)
    val predictedHit    = Bool()
    val predictedWay    = Bool()
    val predictedCall   = Bool()
    val predictedReturn = Bool()
    val historySpeculated = Bool()
    val ghrSnapshot     = UInt(8.W)
    val rasSpSnapshot   = UInt(4.W)
    val hasException    = Bool()
    val ecode           = UInt(6.W)
    val esubcode        = UInt(9.W)
}

/** Minimal decode metadata needed by the pair issue policy. */
class DualIssueInfo extends Bundle {
    val valid         = Bool()
    val src1Read      = Bool()
    val src1          = UInt(5.W)
    val src2Read      = Bool()
    val src2          = UInt(5.W)
    val regWrite      = Bool()
    val dest          = UInt(5.W)
    val isMem         = Bool()
    val isBranch      = Bool()
    val isMdu         = Bool()
    val isSerializing = Bool()
    val hasException  = Bool()
}

class DualPredictResult extends Bundle {
    val hit    = Bool()
    val taken  = Bool()
    val target = UInt(32.W)
    val way = Bool()
    val isConditional = Bool()
    val isCall = Bool()
    val isReturn = Bool()
    val ghrSnapshot = UInt(8.W)
    val rasSpSnapshot = UInt(4.W)
}

class DualPredictorUpdate extends Bundle {
    val valid         = Bool()
    val pc            = UInt(32.W)
    val isBranch      = Bool()
    val isConditional = Bool()
    val taken         = Bool()
    val target        = UInt(32.W)
    val mispredict    = Bool()
    val predictedHit  = Bool()
    val predictedWay  = Bool()
    val predictedCall = Bool()
    val predictedReturn = Bool()
    val historySpeculated = Bool()
    val ghrSnapshot   = UInt(8.W)
    val rasSpSnapshot = UInt(4.W)
    val isCall        = Bool()
    val isReturn      = Bool()
}

object DualIssueBlockReason {
    val Width       = 8
    val None        = 0.U(Width.W)
    val Raw         = (1 << 0).U(Width.W)
    val Waw         = (1 << 1).U(Width.W)
    val Slot0Ctrl   = (1 << 2).U(Width.W)
    val Slot1Mdu    = (1 << 3).U(Width.W)
    val Serializing = (1 << 4).U(Width.W)
    val TwoMem      = (1 << 5).U(Width.W)
    val BranchPair  = (1 << 6).U(Width.W)
    val Exception   = (1 << 7).U(Width.W)
}
