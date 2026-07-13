error id: file://<WORKSPACE>/src/main/scala/mycpu/StageWB.scala:tlbidx_out
file://<WORKSPACE>/src/main/scala/mycpu/StageWB.scala
empty definition using pc, found symbol in pc: tlbidx_out
empty definition using semanticdb
empty definition using fallback
non-local guesses:
	 -chisel3/csr/io/tlbidx_out.
	 -chisel3/csr/io/tlbidx_out#
	 -chisel3/csr/io/tlbidx_out().
	 -chisel3/util/csr/io/tlbidx_out.
	 -chisel3/util/csr/io/tlbidx_out#
	 -chisel3/util/csr/io/tlbidx_out().
	 -csr/io/tlbidx_out.
	 -csr/io/tlbidx_out#
	 -csr/io/tlbidx_out().
	 -scala/Predef.csr.io.tlbidx_out.
	 -scala/Predef.csr.io.tlbidx_out#
	 -scala/Predef.csr.io.tlbidx_out().
offset: 2708
uri: file://<WORKSPACE>/src/main/scala/mycpu/StageWB.scala
text:
```scala
package mycpu

import chisel3._
import chisel3.util._

class StageWB extends Module {
    val io = IO(new Bundle {
        val in = Flipped(Decoupled(new PipelineData()))

        val fwdOut = Output(new ForwardingData())

        val rf_we    = Output(Bool())
        val rf_waddr = Output(UInt(5.W))
        val rf_wdata = Output(UInt(32.W))

        val wb_flush     = Output(Bool())
        val wb_target_pc = Output(UInt(32.W))
        val hw_int_in    = Input(UInt(8.W))
        val wb_has_int   = Output(Bool())

        val mmu_config   = Output(new MmuConfig())

        // ---------------- 新增：对外操作 TLB 的接口 ----------------
        val tlb_we    = Output(Bool())
        val tlb_w_idx = Output(UInt(4.W))
        val tlb_w_dat = Output(new TlbEntry())
        
        val tlb_r_idx = Output(UInt(4.W))
        val tlb_r_dat = Input(new TlbEntry())

        //Debug
        val debug_wb_pc       = Output(UInt(32.W))
        val debug_wb_rf_we    = Output(UInt(4.W))
        val debug_wb_rf_wnum  = Output(UInt(5.W))
        val debug_wb_rf_wdata = Output(UInt(32.W))
    })

    val valid_reg = RegInit(false.B)
    val data_reg  = Reg(new PipelineData())

    val ready_go = true.B
    val allow_in = !valid_reg || ready_go
    io.in.ready := allow_in

    val flush_req = valid_reg && (data_reg.hasException || data_reg.inst_ertn)
    when(flush_req) {
        valid_reg := false.B
    } .elsewhen(allow_in) {
        valid_reg := io.in.valid
    }
    when(io.in.valid && allow_in) { data_reg := io.in.bits }
    
    //CSR
    val is_tlbwr   = data_reg.tlbOp === TlbOp.WR 
    val is_tlbfill = data_reg.tlbOp === TlbOp.FILL
    val is_tlbrd   = data_reg.tlbOp === TlbOp.RD
    val is_tlbsrch = data_reg.tlbOp === TlbOp.SRCH

    val csr = Module(new CSR())
    csr.io.addr      := data_reg.csrNum
    csr.io.writeData := data_reg.ex_result 
    csr.io.writeMask := data_reg.aux_data
    csr.io.writeEn := valid_reg && data_reg.csrWe && !data_reg.hasException

    csr.io.excValid    := valid_reg && data_reg.hasException
    csr.io.excEcode    := data_reg.ecode
    csr.io.excEsubcode := data_reg.esubcode
    csr.io.excPc       := data_reg.pc
    csr.io.excAddr     := Mux(data_reg.ecode === "h08".U, data_reg.pc, data_reg.ex_result)
    
    csr.io.ertnFlush   := valid_reg && data_reg.inst_ertn
    csr.io.hw_int_in   := io.hw_int_in
    
    io.wb_has_int      := csr.io.hasInt
    io.mmu_config      := csr.io.mmu_config

    //tlbfill 的随机索引发生器（自增计数器）
    val rand_idx = RegInit(0.U(4.W))
    rand_idx := rand_idx + 1.U

    //写 TLB (tlbwr / tlbfill)
    io.tlb_we    := valid_reg && !data_reg.hasException && (is_tlbwr || is_tlbfill)
    io.tlb_w_idx := Mux(is_tlbfill, rand_idx, csr.io.t@@lbidx_out)
    io.tlb_w_dat := csr.io.tlb_out

    //读 TLB (tlbrd)
    io.tlb_r_idx := csr.io.tlbidx_out
    csr.io.tlbrd_we := valid_reg && !data_reg.hasException && is_tlbrd
    csr.io.tlbrd_in := io.tlb_r_dat

    // ---------------- 重取指 (Refetch) 与 Flush 逻辑 ----------------
    // 当 CSR 的 DA/PG 变化，或执行了除 tlbsrch 外的 TLB 指令时，清理旧指令
    val do_refetch = valid_reg && !data_reg.hasException && data_reg.is_refetch
    io.wb_flush := valid_reg && (data_reg.hasException || data_reg.inst_ertn || do_refetch)

    // 注意：TLB 重填异常（例外码 3F）使用的是单独的异常入口 tlbrentryOut
    io.wb_target_pc := Mux(data_reg.hasException, 
                           Mux(data_reg.ecode === "h3F".U, csr.io.tlbrentryOut, csr.io.eentryOut),
                       Mux(data_reg.inst_ertn, csr.io.eraOut,
                       data_reg.pc + 4.U)) // Refetch 返回下一条指令继续执行





 
    val final_wb_data = Mux(data_reg.isCsr, csr.io.readData, data_reg.ex_result)
    val rf_we_valid = valid_reg && data_reg.regWriteEn && !data_reg.hasException
    
    io.rf_we    := rf_we_valid
    io.rf_waddr := data_reg.destReg
    io.rf_wdata := final_wb_data

    io.wb_flush     := valid_reg && (data_reg.hasException || data_reg.inst_ertn)
    io.wb_target_pc := Mux(data_reg.hasException, csr.io.eentryOut, csr.io.eraOut)

    io.fwdOut.valid        := valid_reg
    io.fwdOut.regWriteEn   := rf_we_valid
    io.fwdOut.regWriteAddr := data_reg.destReg
    io.fwdOut.result       := final_wb_data
    io.fwdOut.resFromMem   := false.B 
    io.fwdOut.isCsr        := false.B 

    io.debug_wb_pc       := data_reg.pc
    io.debug_wb_rf_we    := Fill(4, rf_we_valid)
    io.debug_wb_rf_wnum  := data_reg.destReg
    io.debug_wb_rf_wdata := final_wb_data
}
```


#### Short summary: 

empty definition using pc, found symbol in pc: tlbidx_out