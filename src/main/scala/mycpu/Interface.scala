package mycpu

import chisel3._
import chisel3.util._

class InstReq extends Bundle {
    val addr = UInt(32.W)
}

class InstMemIO extends Bundle {
    val req  = Decoupled(new InstReq())
    val resp = Flipped(Decoupled(UInt(32.W)))
}

class SramIo extends Bundle {
    val req     = Output(Bool())
    val wr      = Output(Bool())
    val size    = Output(UInt(2.W))
    val wstrb   = Output(UInt(4.W))
    val addr    = Output(UInt(32.W))
    val wdata   = Output(UInt(32.W))

    val addr_ok = Input(Bool())
    val data_ok = Input(Bool())
    val rdata   = Input(UInt(32.W))
}

// 前递网络只把“生产者是否可用、目的寄存器和结果”送到前级。
// 真正的数据选择发生在 EX；ID 仅使用这些元数据判断 load/CSR 等不可前递冒险。
class ForwardingData extends Bundle {
    val valid        = Bool()
    val regWriteEn   = Bool()
    val regWriteAddr = UInt(5.W)
    val result       = UInt(32.W)
    val resFromMem   = Bool()
    val isCsr        = Bool()
}

// EX resolves and trains the predictor only when the instruction leaves EX.
// isBranch=false invalidates a stale BTB entry that predicted a non-branch.
class BranchPredictorUpdate extends Bundle {
    val valid    = Bool()
    val pc       = UInt(32.W)
    val isBranch = Bool()
    val taken    = Bool()
    val target   = UInt(32.W)
}


// 双发射后端每个 lane 的体系结构载荷。DualLaneData 在其外层增加源寄存器
// 元数据和串行化/等待标志，DualPacket 再组合两个按年龄排列的 lane。
class PipelineData extends Bundle{
    //IF Generated
    val pc              = UInt(32.W)
    val inst            = UInt(32.W)
    val predictedHit    = Bool()
    val predictedTaken  = Bool()
    val predictedTarget = UInt(32.W)
    val predictedHistory = UInt(6.W)

    //ID Generated
    //Used in EX
    val aluOp           = UInt(12.W)
    val mduOp           = UInt(7.W)
    val brType          = UInt(9.W)
    val imm             = UInt(32.W)
    val src1IsPC        = Bool()
    val src2IsImm       = Bool()
    val src2IsFour      = Bool()
    val src1_addr       = UInt(5.W)
    val src2_addr       = UInt(5.W)
    val src1_value      = UInt(32.W) //rj
    val src2_value      = UInt(32.W) //rkd
    val resFromMulDiv   = Bool()
    //Used in MEM
    val memWe           = Bool()
    val lsOp            = UInt(8.W)
    //Used in WB
    val resFromMem      = Bool()
    val regWriteEn      = Bool()
    val destReg         = UInt(5.W)
    
    //EX Generated
    val ex_result       = UInt(32.W)
    val aux_data        = UInt(32.W) //csr or memwdata
    // Physical address resolved in M1.  Successful loads overwrite
    // ex_result with their data in M2, so Difftest memory events need a
    // separate address field that survives through retirement.
    val memPaddr        = UInt(32.W)

    //Exception
    val hasException    = Bool()
    val ecode           = UInt(6.W)
    val esubcode        = UInt(9.W)
    val isCsr           = Bool()
    val csrWe           = Bool()
    val csrNum          = UInt(14.W)
    val inst_ertn       = Bool()
    //These two were just used in EX
    val rdtimel         = Bool()
    val rdtimeh         = Bool()
    val isCpucfg        = Bool()

    //TLB
    val tlbOp           = UInt(5.W)
    val invtlb_op       = UInt(5.W)
    val is_refetch      = Bool()
    
    //Cache
    val is_cacop   = Bool()
    val cacop_op   = UInt(5.W)

    val isLL       = Bool()
    val isSC       = Bool()
}


class MmuConfig extends Bundle {
    val crmd    = new CrmdReg()
    val asid    = new AsidReg()
    val dmw0    = new DmwReg()
    val dmw1    = new DmwReg()
    val tlbehi  = new TlbehiReg()
}
