package mycpu

import chisel3._

/** Purely combinational, age-preserving pair issue policy. */
class DualIssueUnit extends Module {
    val io = IO(new Bundle {
        val in         = Input(Vec(2, new DualIssueInfo()))
        val issueValid = Output(Vec(2, Bool()))
        val issueCount = Output(UInt(2.W))
        val blockMask  = Output(UInt(DualIssueBlockReason.Width.W))
    })

    val slot0Writes = io.in(0).regWrite && io.in(0).dest =/= 0.U
    val slot1Writes = io.in(1).regWrite && io.in(1).dest =/= 0.U

    val raw = slot0Writes && (
        (io.in(1).src1Read && io.in(1).src1 === io.in(0).dest) ||
        (io.in(1).src2Read && io.in(1).src2 === io.in(0).dest)
    )
    val waw = slot0Writes && slot1Writes && io.in(0).dest === io.in(1).dest
    val slot0Control = io.in(0).isBranch || io.in(0).isMdu
    val slot1Mdu = io.in(1).isMdu
    val serializing = io.in(0).isSerializing || io.in(1).isSerializing
    val twoMem = io.in(0).isMem && io.in(1).isMem
    val branchPair = io.in(1).isBranch && (
        io.in(0).isBranch || io.in(0).isMem || io.in(0).isMdu
    )
    val exception = io.in(0).hasException || io.in(1).hasException

    val blockMask =
        Mux(raw,           DualIssueBlockReason.Raw,         0.U) |
        Mux(waw,           DualIssueBlockReason.Waw,         0.U) |
        Mux(slot0Control,  DualIssueBlockReason.Slot0Ctrl,   0.U) |
        Mux(slot1Mdu,      DualIssueBlockReason.Slot1Mdu,    0.U) |
        Mux(serializing,   DualIssueBlockReason.Serializing, 0.U) |
        Mux(twoMem,        DualIssueBlockReason.TwoMem,      0.U) |
        Mux(branchPair,    DualIssueBlockReason.BranchPair,  0.U) |
        Mux(exception,     DualIssueBlockReason.Exception,   0.U)

    val canDual = io.in(0).valid && io.in(1).valid && blockMask === 0.U
    io.issueValid(0) := io.in(0).valid
    io.issueValid(1) := canDual
    io.issueCount := Mux(canDual, 2.U, Mux(io.in(0).valid, 1.U, 0.U))
    io.blockMask := Mux(io.in(0).valid && io.in(1).valid, blockMask, 0.U)
}

/** RRD availability downgrade without allowing lane 1 to overtake lane 0. */
class DualIssueAvailability extends Module {
    val io = IO(new Bundle {
        val structuralCount = Input(UInt(2.W))
        val lane1Selected = Input(Bool())
        val blocked = Input(Vec(2, Bool()))
        val selectedCount = Output(UInt(2.W))
    })

    io.selectedCount := Mux(io.blocked(0), 0.U,
        Mux(io.lane1Selected && io.blocked(1), 1.U,
            io.structuralCount))
}
