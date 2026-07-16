package mycpu

import chisel3._
import chisel3.util._
import chisel3.util.BitPat

class StageID extends Module {
    val io = IO(new Bundle {
        val in  = Flipped(Decoupled(new PipelineData()))
        val out = Decoupled(new PipelineData())

        val rf_raddr1 = Output(UInt(5.W))
        val rf_rdata1 = Input(UInt(32.W))
        val rf_raddr2 = Output(UInt(5.W))
        val rf_rdata2 = Input(UInt(32.W))

        val fwdFromEx  = Input(new ForwardingData())
        val fwdFromMem = Input(new ForwardingData())
        val has_int    = Input(Bool())

        val flush = Input(Bool())
    })
    // ID 流水寄存器。此级完成预译码、完整译码、双口寄存器读取和数据冒险判断，
    // 但不在 ID 选择前递值；源操作数连同寄存器号送到 EX 后再旁路修正。
    val valid_reg = RegInit(false.B)
    val data_reg  = RegInit(0.U.asTypeOf(new PipelineData()))

    //Pre-Decode
    val op6 = data_reg.inst(31, 26)
    val is_store = (op6 === "b001010".U) && (data_reg.inst(24) === 1.U)
    val is_branch = (data_reg.inst(31, 26) === BitPat("b01011?")) || (data_reg.inst(31, 26) === BitPat("b0110??"))
    val is_csr_write = (data_reg.inst(31, 24) === "h04".U) && (data_reg.inst(9, 5) =/= 0.U)
    val src2IsRd = is_store || is_branch || is_csr_write

    val src1_addr = data_reg.inst(9, 5) //rj
    val src2_addr = Mux(src2IsRd, data_reg.inst(4, 0), data_reg.inst(14, 10)) //rkd
    io.rf_raddr1 := src1_addr
    io.rf_raddr2 := src2_addr

    val decoder = Module(new Decoder())
    decoder.io.inst := data_reg.inst
    val dec = decoder.io.out
    
    // ALU 结果可在下一条指令进入 EX 时由 MEM/WB 前递，不必停顿。
    // Load 数据到 MEM 的 data_ok 才产生，CSR 读值到 WB 才产生，因此相关指令
    // 在生产者位于 EX 或 MEM 时都停顿；这也避免把 MEM 的访存地址误当前递结果。
    val ex_is_load  = io.fwdFromEx.valid && io.fwdFromEx.resFromMem && io.fwdFromEx.regWriteEn
    val ex_is_csr   = io.fwdFromEx.valid && io.fwdFromEx.isCsr
    val mem_is_csr  = io.fwdFromMem.valid && io.fwdFromMem.isCsr
    val mem_is_load = io.fwdFromMem.valid && io.fwdFromMem.resFromMem && io.fwdFromMem.regWriteEn

    val is_tlb_inst = dec.tlbOp =/= 0.U 
    val csr_hazard  = is_tlb_inst && (ex_is_csr || mem_is_csr)
    
    val ex_conflict_s1 = (ex_is_load || ex_is_csr) && dec.src1_read && (io.fwdFromEx.regWriteAddr === src1_addr) && (src1_addr =/= 0.U)
    val ex_conflict_s2 = (ex_is_load || ex_is_csr) && dec.src2_read && (io.fwdFromEx.regWriteAddr === src2_addr) && (src2_addr =/= 0.U)
    val mem_conflict_s1 = (mem_is_csr || mem_is_load) && dec.src1_read && (io.fwdFromMem.regWriteAddr === src1_addr) && (src1_addr =/= 0.U)
    val mem_conflict_s2 = (mem_is_csr || mem_is_load) && dec.src2_read && (io.fwdFromMem.regWriteAddr === src2_addr) && (src2_addr =/= 0.U)

    val stall = ex_conflict_s1 || ex_conflict_s2 || mem_conflict_s1 || mem_conflict_s2 || csr_hazard

    // 在 ID -> EX 流水寄存器之前完成旁路选择。这样相关指令进入 EX 后，
    // 操作数已经稳定，不再需要用当前 MEM/WB 的 destReg 驱动 EX 地址计算、
    // TLB 查询和异常判断。EX 优先于 MEM；WB 同拍写回由 Regfile 的
    // write-first 旁路覆盖。
    val ex_fwd_s1 = io.fwdFromEx.valid && io.fwdFromEx.regWriteEn && dec.src1_read &&
                    (io.fwdFromEx.regWriteAddr === src1_addr) && (src1_addr =/= 0.U)
    val ex_fwd_s2 = io.fwdFromEx.valid && io.fwdFromEx.regWriteEn && dec.src2_read &&
                    (io.fwdFromEx.regWriteAddr === src2_addr) && (src2_addr =/= 0.U)
    val mem_fwd_s1 = io.fwdFromMem.valid && io.fwdFromMem.regWriteEn && dec.src1_read &&
                     (io.fwdFromMem.regWriteAddr === src1_addr) && (src1_addr =/= 0.U)
    val mem_fwd_s2 = io.fwdFromMem.valid && io.fwdFromMem.regWriteEn && dec.src2_read &&
                     (io.fwdFromMem.regWriteAddr === src2_addr) && (src2_addr =/= 0.U)

    val resolved_src1 = Mux(ex_fwd_s1, io.fwdFromEx.result,
                        Mux(mem_fwd_s1, io.fwdFromMem.result, io.rf_rdata1))
    val resolved_src2 = Mux(ex_fwd_s2, io.fwdFromEx.result,
                        Mux(mem_fwd_s2, io.fwdFromMem.result, io.rf_rdata2))
    
    // 标准弹性流水级：空级可收数据；本级完成且下级 ready 时可同拍“出旧进新”。
    // stall 拉低 ready_go 后，反压会沿 EX <- ID <- IF 一直传播。
    val ready_go = !stall
    val allow_in = !valid_reg || (ready_go && io.out.ready)
    io.in.ready := allow_in
    when(io.flush) {
        valid_reg := false.B
    } .elsewhen(allow_in) {
        valid_reg := io.in.valid
    }
    when(io.in.valid && allow_in) { data_reg := io.in.bits }
    
    val out_data = WireDefault(data_reg)
    out_data.aluOp         := dec.aluOp
    out_data.mduOp         := dec.mduOp
    out_data.brType        := dec.brType
    out_data.imm           := dec.imm
    out_data.src1IsPC      := dec.src1IsPC
    out_data.src2IsImm     := dec.src2IsImm
    out_data.src2IsFour    := dec.src2IsFour
    out_data.memWe         := dec.memWe
    out_data.resFromMem    := dec.resFromMem
    out_data.resFromMulDiv := dec.resFromMulDiv
    out_data.lsOp          := dec.lsOp
    out_data.regWriteEn    := dec.regWe
    out_data.destReg       := dec.destReg
    out_data.isCsr         := dec.isCsr
    out_data.csrWe         := dec.csrWe
    out_data.csrNum        := dec.csrNum
    out_data.inst_ertn     := dec.inst_ertn
    out_data.rdtimel       := dec.rdtimel
    out_data.rdtimeh       := dec.rdtimeh
    out_data.isCpucfg      := dec.isCpucfg
    
    out_data.src1_addr     := src1_addr
    out_data.src2_addr     := src2_addr
    // 只有 ready_go && io.out.ready 时这些值才会被 EX 锁存。若前方是
    // load/CSR，原有 stall 会一直保持，直到能从 WB/Regfile 得到真实结果。
    out_data.src1_value    := resolved_src1
    out_data.src2_value    := resolved_src2

    val exc_int = io.has_int
    out_data.hasException  := exc_int || data_reg.hasException || dec.hasException
    out_data.ecode         := Mux(exc_int, 0.U(6.W), Mux(data_reg.hasException, data_reg.ecode, dec.ecode))
    out_data.esubcode      := 0.U(9.W)

    out_data.tlbOp         := dec.tlbOp
    out_data.invtlb_op     := dec.invtlb_op
    out_data.is_refetch    := dec.is_refetch

    out_data.is_cacop  := dec.is_cacop
    out_data.cacop_op  := dec.cacop_op

    io.out.valid := valid_reg && ready_go && !io.flush
    io.out.bits  := out_data
    

}
