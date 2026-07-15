package mycpu

import chisel3._
import chisel3.util._

class StageMEM extends Module {
    val io = IO(new Bundle {
        val in  = Flipped(Decoupled(new PipelineData()))
        val out = Decoupled(new PipelineData())

        val fwdOut = Output(new ForwardingData())
        
        // 监控探头：捕获EX级发出的请求
        val sram_req     = Input(Bool())
        val sram_addr_ok = Input(Bool())

        val sram_rdata = Input(UInt(32.W))
        val sram_data_ok = Input(Bool())

        val mem_has_exc_out = Output(Bool())

        val flush = Input(Bool())

        val current_valid = Output(Bool())
    })

    // MEM 是阻塞式访存完成级：普通 ALU 指令可立即通过；load/store/CACOP 必须等
    // data_ok 才能前进。load 在这里按地址低位选字节/半字并做符号或零扩展。
    // flush 时若 Cache 中已有请求，discard_reg 会吞掉迟到响应，防止匹配到新指令。
    val valid_reg = RegInit(false.B)
    val data_reg  = RegInit(0.U.asTypeOf(new PipelineData()))

    val is_mem = (data_reg.memWe || data_reg.resFromMem || data_reg.is_cacop) && valid_reg && !data_reg.hasException
    
    // ==========================================
    // 幽灵数据防御系统（已打破死循环）
    // ==========================================
    val ex_req_fire = io.sram_req && io.sram_addr_ok
    val outstanding_req = is_mem || ex_req_fire
    val discard_reg = RegInit(false.B)

    // 置位条件
    val set_discard = outstanding_req && io.flush && !io.sram_data_ok

    when(set_discard) {
        discard_reg := true.B
    } .elsewhen(discard_reg && io.sram_data_ok) {
        discard_reg := false.B
    }

    // 这里去掉了导致死循环的 !set_discard，直接用寄存器过滤
    val real_data_ok = io.sram_data_ok && !discard_reg
    
    // 核心握手逻辑：是访存指令就死等 real_data_ok
    val ready_go = !is_mem || real_data_ok
    // ==========================================

    val allow_in = !valid_reg || (ready_go && io.out.ready)
    io.in.ready := allow_in

    when(io.flush) {
        valid_reg := false.B
    } .elsewhen(allow_in) {
        valid_reg := io.in.valid
    }
    when(io.in.valid && allow_in) { data_reg := io.in.bits }
    
    val current_has_exc = valid_reg && (data_reg.hasException || data_reg.inst_ertn)
    io.mem_has_exc_out := current_has_exc
    
    val rdata = io.sram_rdata
    val offset = data_reg.ex_result(1, 0) 

    val byte_data = MuxLookup(offset, 0.U(8.W))(Seq(
        0.U -> rdata(7, 0),
        1.U -> rdata(15, 8),
        2.U -> rdata(23, 16),
        3.U -> rdata(31, 24)
    ))
    val half_data = Mux(offset(1), rdata(31, 16), rdata(15, 0))

    val load_result = MuxLookup(data_reg.lsOp, rdata)(Seq(
        LsOp.LD_B  -> Cat(Fill(24, byte_data(7)), byte_data),
        LsOp.LD_BU -> Cat(0.U(24.W), byte_data),
        LsOp.LD_H  -> Cat(Fill(16, half_data(15)), half_data),
        LsOp.LD_HU -> Cat(0.U(16.W), half_data),
        LsOp.LD_W  -> rdata
    ))

    val final_result = Mux(data_reg.resFromMem && !data_reg.hasException, load_result, data_reg.ex_result)

    val out_data = WireDefault(data_reg)
    out_data.ex_result := final_result

    io.out.valid := valid_reg && ready_go && !io.flush
    io.out.bits  := out_data

    io.fwdOut.valid        := valid_reg
    io.fwdOut.regWriteEn   := data_reg.regWriteEn
    io.fwdOut.regWriteAddr := data_reg.destReg
    io.fwdOut.result       := data_reg.ex_result
    io.fwdOut.resFromMem   := data_reg.resFromMem
    io.fwdOut.isCsr        := data_reg.isCsr

    io.current_valid := valid_reg
}
