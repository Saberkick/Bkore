error id: file://<WORKSPACE>/src/main/scala/mycpu/StageIf.scala:chisel3/util/ReadyValidIO#valid.
file://<WORKSPACE>/src/main/scala/mycpu/StageIf.scala
empty definition using pc, found symbol in pc: 
found definition using semanticdb; symbol chisel3/util/ReadyValidIO#valid.
empty definition using fallback
non-local guesses:

offset: 1036
uri: file://<WORKSPACE>/src/main/scala/mycpu/StageIf.scala
text:
```scala
package mycpu

import chisel3._
import chisel3.util._

class StageIF extends Module {
    val io = IO(new Bundle {
        //For Stage ID
        val out             = Decoupled(new PipelineData())

        val flush           = Input(Bool())
        val flush_target_pc = Input(UInt(32.W))
        //For mem
        val inst_sram       = new SramIo()
    })

    val pc_reg = RegInit(Config.START_PC)

    //MODDED in AXI experiment
    val wait_data_reg = RegInit(false.B)
    val discard_reg   = RegInit(false.B)
    val buf_valid     = RegInit(false.B)
    val inst_buffer   = Reg(UInt(32.W))

    val allow_req = !wait_data_reg && !buf_valid && io.out.ready
    val req_valid = allow_req && !io.flush

    io.inst_sram.req    := req_valid
    io.inst_sram.wr     := false.B
    io.inst_sram.size   := 2.U
    io.inst_sram.wstrb  := 0.U
    io.inst_sram.addr   := pc_reg
    io.inst_sram.wdata  := 0.U




    val next_pc = Mux(io.flush, io.flush_target_pc - 4.U, pc_reg + 4.U)

    io.imem.req.bits.addr := next_pc
    io.imem.req.@@valid := !io.flush && io.out.ready //if ID is ok to receive
    io.imem.resp.ready := io.out.ready
    when(io.flush || io.imem.req.fire) { pc_reg := next_pc}


    when(io.flush) {
        buf_valid := false.B
    } .elsewhen(io.imem.resp.valid && !io.out.ready) {
        inst_buffer := io.imem.resp.bits
        buf_valid   := true.B
    } .elsewhen(io.out.ready) {
        buf_valid   := false.B
    }

    val final_inst  = Mux(buf_valid, inst_buffer, io.imem.resp.bits)
    val final_valid = (io.imem.resp.valid || buf_valid) && !io.flush

    val pc_alignment_error = (pc_reg(1, 0) =/= 0.U)
    val safe_inst = Mux(pc_alignment_error, "h03400000".U(32.W), io.imem.resp.bits)

    val out_data = WireDefault(0.U.asTypeOf(new PipelineData()))
    out_data.pc           := pc_reg
    out_data.inst         := safe_inst
    out_data.hasException := pc_alignment_error
    out_data.ecode        := Mux(pc_alignment_error, "h08".U(6.W), 0.U(6.W))
    
    io.out.bits  := out_data
    io.out.valid := final_valid
}

class IF_SramAdapter extends Module {
    val io = IO(new Bundle {
        val imem = Flipped(new InstMemIO())
        val sram = new SramIo()
    })

    io.sram.en    := io.imem.req.valid
    io.sram.we    := 0.U(4.W)
    io.sram.wdata := 0.U(32.W)
    io.sram.addr  := Cat(io.imem.req.bits.addr(31, 2), 0.U(2.W))
    io.imem.req.ready := true.B 

    io.imem.resp.valid := RegNext(io.imem.req.valid, init = false.B)
    io.imem.resp.bits  := io.sram.rdata
}
```


#### Short summary: 

empty definition using pc, found symbol in pc: 