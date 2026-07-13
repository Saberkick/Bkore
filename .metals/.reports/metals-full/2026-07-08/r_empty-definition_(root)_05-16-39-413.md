error id: file://<WORKSPACE>/src/main/scala/mycpu/StageEX.scala:mycpu/StageEX#mem_req_valid.
file://<WORKSPACE>/src/main/scala/mycpu/StageEX.scala
empty definition using pc, found symbol in pc: 
empty definition using semanticdb
empty definition using fallback
non-local guesses:
	 -chisel3/mem_req_valid.
	 -chisel3/mem_req_valid#
	 -chisel3/mem_req_valid().
	 -chisel3/util/mem_req_valid.
	 -chisel3/util/mem_req_valid#
	 -chisel3/util/mem_req_valid().
	 -mem_req_valid.
	 -mem_req_valid#
	 -mem_req_valid().
	 -scala/Predef.mem_req_valid.
	 -scala/Predef.mem_req_valid#
	 -scala/Predef.mem_req_valid().
offset: 7381
uri: file://<WORKSPACE>/src/main/scala/mycpu/StageEX.scala
text:
```scala
package mycpu

import chisel3._
import chisel3.util._

class StageEX extends Module {
    val io = IO(new Bundle {
        val in  = Flipped(Decoupled(new PipelineData()))
        val out = Decoupled(new PipelineData())

        val fwdFromMem = Input(new ForwardingData())
        val fwdFromWb  = Input(new ForwardingData())
        val fwdOut = Output(new ForwardingData())

        val branch_req = Output(Bool())
        val branch_pc  = Output(UInt(32.W))

        val flush = Input(Bool())

        val timer_in   = Input(UInt(64.W))
        val data_sram  = new SramIo()

        val mem_has_exc_in = Input(Bool())
    })

    val valid_reg = RegInit(false.B)
    val data_reg  = Reg(new PipelineData())

    //MDU FSM
    val is_div = (data_reg.mduOp === MduOp.DIV_W || data_reg.mduOp === MduOp.MOD_W || 
                  data_reg.mduOp === MduOp.DIV_WU || data_reg.mduOp === MduOp.MOD_WU) && !data_reg.hasException
    
    val is_mul = (data_reg.mduOp === MduOp.MUL_W || data_reg.mduOp === MduOp.MULH_W || 
                  data_reg.mduOp === MduOp.MULH_WU) && !data_reg.hasException
    
    val is_mdu = data_reg.resFromMulDiv && !data_reg.hasException

    val mdu_busy = RegInit(false.B)
    val mdu_finished = RegInit(false.B)
    val div_done = WireDefault(false.B)
    val mul_done = RegNext(valid_reg && is_mul && !mdu_busy && !io.flush, false.B) 

    val mdu_ready = !is_mdu || mdu_finished || (mdu_busy && (div_done || mul_done))
    val ready_go = Wire(Bool()) //MODDED in AXI experiment
    val allow_in = !valid_reg || (ready_go && io.out.ready)

    when(io.flush) {
        mdu_busy := false.B
        mdu_finished := false.B
    } .elsewhen(valid_reg && is_mdu && !mdu_busy && !mdu_finished) {
        mdu_busy := true.B
    } .elsewhen(mdu_busy && (div_done || mul_done)) {
        mdu_busy := false.B
        mdu_finished := !io.out.ready 
    } .elsewhen(valid_reg && ready_go && io.out.ready) {
        mdu_finished := false.B 
    }
    

    //Pipeline Settings
    io.in.ready := allow_in
    when(io.flush) {
        valid_reg := false.B
    } .elsewhen(allow_in) {
        valid_reg := io.in.valid
    }
    when(io.in.valid && allow_in) { data_reg := io.in.bits }
    

    //Forwarding Path
    val s1_mem_hit = io.fwdFromMem.valid && io.fwdFromMem.regWriteEn && (io.fwdFromMem.regWriteAddr === data_reg.src1_addr) && (data_reg.src1_addr =/= 0.U)
    val s1_wb_hit  = io.fwdFromWb.valid  && io.fwdFromWb.regWriteEn  && (io.fwdFromWb.regWriteAddr === data_reg.src1_addr) && (data_reg.src1_addr =/= 0.U)
    val s2_mem_hit = io.fwdFromMem.valid && io.fwdFromMem.regWriteEn && (io.fwdFromMem.regWriteAddr === data_reg.src2_addr) && (data_reg.src2_addr =/= 0.U)
    val s2_wb_hit  = io.fwdFromWb.valid  && io.fwdFromWb.regWriteEn  && (io.fwdFromWb.regWriteAddr === data_reg.src2_addr) && (data_reg.src2_addr =/= 0.U)

    val src1_fwd = MuxCase(data_reg.src1_value, Seq(s1_mem_hit -> io.fwdFromMem.result, s1_wb_hit -> io.fwdFromWb.result))
    val src2_fwd = MuxCase(data_reg.src2_value, Seq(s2_mem_hit -> io.fwdFromMem.result, s2_wb_hit -> io.fwdFromWb.result))

    //Branch 
    val eq  = (src1_fwd === src2_fwd)
    val lt  = (src1_fwd.asSInt < src2_fwd.asSInt)
    val ltu = (src1_fwd < src2_fwd)

    val branch_taken = MuxLookup(data_reg.brType, false.B)(Seq(
        BrType.BEQ  -> eq,      BrType.BNE  -> !eq,     BrType.BLT  -> lt,
        BrType.BGE  -> !lt,     BrType.BLTU -> ltu,     BrType.BGEU -> !ltu,
        BrType.JIRL -> true.B,  BrType.B   -> true.B,   BrType.BL  -> true.B
    ))
    val br_base = Mux(data_reg.brType === BrType.JIRL, src1_fwd, data_reg.pc)
    io.branch_req := valid_reg && branch_taken && !data_reg.hasException && io.out.ready
    io.branch_pc  := br_base + data_reg.imm

    //ALU
    val alu_src1 = Mux(data_reg.src1IsPC, data_reg.pc, src1_fwd)
    val alu_src2 = Mux(data_reg.src2IsImm, data_reg.imm, Mux(data_reg.src2IsFour, 4.U, src2_fwd))

    val alu = Module(new ALU())
    alu.io.aluOp := data_reg.aluOp
    alu.io.src1  := alu_src1
    alu.io.src2  := alu_src2
    val alu_res  = alu.io.res

    //MDU
    //MODDED
    val mdu_src1_reg = Reg(UInt(32.W))
    val mdu_src2_reg = Reg(UInt(32.W))

    when(valid_reg && !mdu_busy && !mdu_finished) {
        mdu_src1_reg := src1_fwd
        mdu_src2_reg := src2_fwd
    }

    val real_mdu_src1 = Mux(mdu_busy || mdu_finished, mdu_src1_reg, src1_fwd)
    val real_mdu_src2 = Mux(mdu_busy || mdu_finished, mdu_src2_reg, src2_fwd)

    val is_signed_mdu = data_reg.mduOp === MduOp.MULH_W || data_reg.mduOp === MduOp.DIV_W || data_reg.mduOp === MduOp.MOD_W
    val mul = Module(new Multiplier())
    mul.io.src1     := real_mdu_src1
    mul.io.src2     := real_mdu_src2
    mul.io.isSigned := is_signed_mdu
    
    val div = Module(new Divider())
    val div_src1_abs = Mux(is_signed_mdu && src1_fwd(31), (~src1_fwd + 1.U), real_mdu_src1)
    val div_src2_abs = Mux(is_signed_mdu && src2_fwd(31), (~src2_fwd + 1.U), real_mdu_src2)
    
    div.io.enable := valid_reg && is_div && !mdu_busy && !mdu_finished && !io.flush
    div.io.a      := div_src1_abs
    div.io.b      := div_src2_abs
    div_done      := div.io.done 
    
    val q_sign = real_mdu_src1(31) ^ real_mdu_src2(31)
    val r_sign = real_mdu_src1(31)
    val final_q = Mux(is_signed_mdu && q_sign, (~div.io.q + 1.U), div.io.q)
    val final_r = Mux(is_signed_mdu && r_sign, (~div.io.r + 1.U), div.io.r)

    val mdu_res = MuxLookup(data_reg.mduOp, 0.U(32.W))(Seq(
        MduOp.MUL_W   -> mul.io.result64(31, 0),
        MduOp.MULH_W  -> mul.io.result64(63, 32),
        MduOp.MULH_WU -> mul.io.result64(63, 32),
        MduOp.DIV_W   -> final_q,
        MduOp.MOD_W   -> final_r,
        MduOp.DIV_WU  -> div.io.q,
        MduOp.MOD_WU  -> div.io.r
    ))

    //
    val csr_mask = Mux(data_reg.src1_addr === 0.U, 0.U(32.W),
                   Mux(data_reg.src1_addr === 1.U, "hFFFFFFFF".U(32.W),
                   src1_fwd))
    val final_ex_result = Mux(data_reg.rdtimel, io.timer_in(31, 0),
                          Mux(data_reg.rdtimeh, io.timer_in(63, 32),
                          Mux(data_reg.isCsr, src2_fwd, 
                          Mux(data_reg.resFromMulDiv, mdu_res, alu_res))))
    val aux_data        = Mux(data_reg.isCsr, csr_mask, src2_fwd)

    //DataMEM
    //Memory Check
    val isWord = data_reg.lsOp === LsOp.LD_W || data_reg.lsOp === LsOp.ST_W
    val isHalf = data_reg.lsOp === LsOp.LD_H || data_reg.lsOp === LsOp.LD_HU || data_reg.lsOp === LsOp.ST_H
    val ale = (data_reg.resFromMem || data_reg.memWe) && valid_reg && 
              ((isWord && (alu_res(1, 0) =/= 0.U)) || (isHalf && alu_res(0) === 1.U))
    //MODDED in AXI experiment
    val is_mem = (data_reg.resFromMem || data_reg.memWe) && valid_reg && !data_reg.hasException && !ale && !io.mem_has_exc_in
    val mem_req_sent = RegInit(false.B)
    val req_fire = io.data_sram.req && io.data_sram.addr_ok

    when(io.flush || (valid_reg && ready_go && io.out.ready)) {
        mem_req_sent := false.B
    } .elsewhen(req_fire) {
        mem_req_sent := true.B
    }
    //Read/Write DataMEM
    val stMaskB = "b0001".U(4.W) << alu_res(1, 0)
    val stMaskH = Mux(alu_res(1), "b1100".U(4.W), "b0011".U(4.W))
    val stMaskW = "b1111".U(4.W)

    io.data_sram.req   := is_mem && !mem_req_sent && io.out.ready && !io.flush
    io.data_sram.en   := (data_reg.memWe || data_reg.resFromMem) && @@mem_req_valid && !io.flush
    io.data_sram.we   := Mux(data_reg.memWe && mem_req_valid && !io.flush, 
                         Mux(isWord, stMaskW, Mux(isHalf, stMaskH, stMaskB)), 0.U(4.W))
    io.data_sram.addr := alu_res

    val wdata_b = Fill(4, src2_fwd(7, 0))
    val wdata_h = Fill(2, src2_fwd(15, 0))
    io.data_sram.wdata := Mux(isWord, src2_fwd, Mux(isHalf, wdata_h, wdata_b))

    //
    val out_data = WireDefault(data_reg) 
    out_data.ex_result := final_ex_result
    out_data.aux_data  := aux_data
    out_data.hasException := data_reg.hasException || ale
    out_data.ecode        := Mux(data_reg.hasException, data_reg.ecode, Mux(ale, "h09".U(6.W), 0.U))

    io.out.valid := valid_reg && ready_go && !io.flush
    io.out.bits  := out_data

    io.fwdOut.valid        := valid_reg
    io.fwdOut.regWriteEn   := data_reg.regWriteEn
    io.fwdOut.regWriteAddr := data_reg.destReg
    io.fwdOut.resFromMem   := data_reg.resFromMem
    io.fwdOut.result       := DontCare
    io.fwdOut.isCsr        := data_reg.isCsr
}

```


#### Short summary: 

empty definition using pc, found symbol in pc: 