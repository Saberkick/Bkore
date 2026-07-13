error id: file://<WORKSPACE>/src/main/scala/mycpu/CSR.scala:mycpu/CSR#tlbehi_vppn.
file://<WORKSPACE>/src/main/scala/mycpu/CSR.scala
empty definition using pc, found symbol in pc: 
found definition using semanticdb; symbol mycpu/CSR#tlbehi_vppn.
empty definition using fallback
non-local guesses:

offset: 2826
uri: file://<WORKSPACE>/src/main/scala/mycpu/CSR.scala
text:
```scala
package mycpu

import chisel3._
import chisel3.util._

class CSR extends Module {
    val io = IO(new Bundle {
        val addr        = Input(UInt(14.W))
        val readData    = Output(UInt(32.W))
        
        val writeEn     = Input(Bool())
        val writeData   = Input(UInt(32.W))
        val writeMask   = Input(UInt(32.W))
        
        val eentryOut   = Output(UInt(32.W))
        val eraOut      = Output(UInt(32.W))
        val hasInt      = Output(Bool())
        
        val excValid    = Input(Bool())
        val excEcode    = Input(UInt(6.W))
        val excEsubcode = Input(UInt(9.W))
        val excPc       = Input(UInt(32.W))
        val excAddr     = Input(UInt(32.W))
        
        val ertnFlush   = Input(Bool())
        val hw_int_in   = Input(UInt(8.W))
    })

    def maskedWrite(reg: UInt, wdata: UInt, wmask: UInt): UInt = {
        (reg & ~wmask) | (wdata & wmask)
    }

    //Current Mode
    //Privilege Level : 0 kernel, 3 user
    val crmd_plv    = RegInit(0.U(2.W))
    //Interrupt Enable
    val crmd_ie     = RegInit(0.U(1.W))
    //Direct Address Translation Enable
    val crmd_da      = RegInit(1.U(1.W))
    //Paging Translation Enable
    val crmd_pg      = RegInit(0.U(1.W))
    //Previous Mode
    //Previous Privilege Level
    val prmd_pplv   = RegInit(0.U(2.W))
    //Previous Interrupt Enable
    val prmd_pie    = RegInit(0.U(1.W))
    //Exception Config
    //Local Interrupt Enable : 1:0 Software, 9:2 Hardware, 11 Timer, 12 IPI
    val ecfg_lie_ipi   = RegInit(0.U(1.W))
    val ecfg_lie_timer = RegInit(0.U(1.W))
    val ecfg_lie_hw    = RegInit(0.U(8.W))
    val ecfg_lie_sw    = RegInit(0.U(2.W))
    val ecfg_lie = Cat(ecfg_lie_ipi, ecfg_lie_timer, 0.U(1.W), ecfg_lie_hw, ecfg_lie_sw)
    //Exception Status
    //Is Software Interrupt
    val estat_is_sw    = RegInit(0.U(2.W))
    //Is Timer Interrupt
    val estat_is_timer = RegInit(0.U(1.W))
    val estat_ecode    = RegInit(0.U(6.W))
    val estat_esubcode = RegInit(0.U(9.W))
    //Exception Return Address
    val eraReg      = RegInit(0.U(32.W))
    //Bad Virtual Address
    val badvReg     = RegInit(0.U(32.W))
    //Exception Entry
    val eentry_va   = RegInit(0.U(26.W))
    //Save Registers
    val save0Reg    = RegInit(0.U(32.W))
    val save1Reg    = RegInit(0.U(32.W))
    val save2Reg    = RegInit(0.U(32.W))
    val save3Reg    = RegInit(0.U(32.W))
    //Timer ID
    val tidReg      = RegInit(0.U(32.W))
    //Timer Config
    //0: En, 1: Periodic, 31:2 InitVal
    val tcfgReg     = RegInit(0.U(32.W))
    val timer_cnt   = RegInit("hffffffff".U(32.W))

    //TLB Index Register
    val tlbidx_index = RegInit(0.U(4.W))
    val tlbidx_ps    = RegInit(0.U(6.W))
    //No Entry (TLB missed)
    val tlbidx_ne    = RegInit(0.U(1.W))

    //// TLB Entry High Register
    val tlbehi_vppn@@  = RegInit(0.U(19.W))



    val estat = Cat(0.U(1.W), estat_is_timer, 0.U(1.W), io.hw_int_in, estat_is_sw)
    io.hasInt := ((ecfg_lie & estat) =/= 0.U) && (crmd_ie === 1.U)
    val tcfg_en       = tcfgReg(0)
    val tcfg_periodic = tcfgReg(1)
    val tcfg_initval  = tcfgReg(31, 2)

    when(io.writeEn){
        switch(io.addr){
            //CRMD
            is("h00".U) { 
                crmd_plv            := maskedWrite(crmd_plv, io.writeData(1, 0), io.writeMask(1, 0))
                crmd_ie             := maskedWrite(crmd_ie,  io.writeData(2),    io.writeMask(2))
            }
            //PRMD
            is("h01".U) { 
                prmd_pplv           := maskedWrite(prmd_pplv, io.writeData(1, 0), io.writeMask(1, 0))
                prmd_pie            := maskedWrite(prmd_pie,  io.writeData(2),    io.writeMask(2))
            }
            //ECFG
            is("h04".U) { 
                ecfg_lie_sw         := maskedWrite(ecfg_lie_sw,    io.writeData(1, 0), io.writeMask(1, 0))
                ecfg_lie_hw         := maskedWrite(ecfg_lie_hw,    io.writeData(9, 2), io.writeMask(9, 2))
                ecfg_lie_timer      := maskedWrite(ecfg_lie_timer, io.writeData(11),   io.writeMask(11))
                ecfg_lie_ipi        := maskedWrite(ecfg_lie_ipi,   io.writeData(12),   io.writeMask(12))
            }
            //ESTAT
            is("h05".U) { estat_is_sw         := maskedWrite(estat_is_sw, io.writeData(1, 0), io.writeMask(1, 0)) }

            is("h06".U) { eraReg    := maskedWrite(eraReg, io.writeData, io.writeMask) }
            is("h07".U) { badvReg   := maskedWrite(badvReg, io.writeData, io.writeMask) }
            is("h0c".U) { eentry_va := maskedWrite(eentry_va, io.writeData(31, 6), io.writeMask(31, 6)) }

            is("h30".U) { save0Reg  := maskedWrite(save0Reg, io.writeData, io.writeMask) }
            is("h31".U) { save1Reg  := maskedWrite(save1Reg, io.writeData, io.writeMask) }
            is("h32".U) { save2Reg  := maskedWrite(save2Reg, io.writeData, io.writeMask) }
            is("h33".U) { save3Reg  := maskedWrite(save3Reg, io.writeData, io.writeMask) }
            is("h40".U) { tidReg    := maskedWrite(tidReg, io.writeData, io.writeMask) }
            //TCFG
            is("h41".U) { tcfgReg   := maskedWrite(tcfgReg, io.writeData, io.writeMask) }
            //TICLR
            is("h44".U) { 
                when((io.writeMask(0) & io.writeData(0)) === 1.U) { estat_is_timer := 0.U}
            }

        }
    }
    val tcfg_next_value = maskedWrite(tcfgReg, io.writeData, io.writeMask)
    val is_writing_tcfg = io.writeEn && (io.addr === "h41".U)

    when(is_writing_tcfg && tcfg_next_value(0) === 1.U) {
        timer_cnt := Cat(tcfg_next_value(31, 2), 0.U(2.W))
    } .elsewhen(tcfg_en === 1.U && timer_cnt =/= "hffffffff".U) {
        when(timer_cnt === 0.U) {
            estat_is_timer := 1.U
            timer_cnt := Mux(tcfg_periodic === 1.U, Cat(tcfg_initval, 0.U(2.W)), "hffffffff".U(32.W))
        } .otherwise {
            timer_cnt := timer_cnt - 1.U
        }
    }

    when(io.excValid) {
        prmd_pplv       := crmd_plv
        prmd_pie        := crmd_ie
        crmd_plv        := 0.U
        crmd_ie         := 0.U
        eraReg          := io.excPc
        estat_ecode     := io.excEcode
        estat_esubcode  := io.excEsubcode
        //ADEF(0x8) or ALE(0x9) 
        when(io.excEcode === "h08".U || io.excEcode === "h09".U) { badvReg := io.excAddr}
    } .elsewhen(io.ertnFlush) {
        crmd_plv        := prmd_pplv
        crmd_ie         := prmd_pie
    }

    io.readData := 0.U 
    switch(io.addr) {
        is("h00".U) { io.readData := Cat(0.U(28.W), 1.U(1.W), crmd_ie, crmd_plv) }
        is("h01".U) { io.readData := Cat(0.U(29.W), prmd_pie, prmd_pplv) }
        is("h04".U) { io.readData := Cat(0.U(19.W), ecfg_lie) }
        is("h05".U) { io.readData := Cat(0.U(1.W), estat_esubcode, estat_ecode, 0.U(3.W), estat) }
        is("h06".U) { io.readData := eraReg }
        is("h07".U) { io.readData := badvReg }
        is("h0c".U) { io.readData := Cat(eentry_va, 0.U(6.W)) }
        is("h30".U) { io.readData := save0Reg }
        is("h31".U) { io.readData := save1Reg }
        is("h32".U) { io.readData := save2Reg }
        is("h33".U) { io.readData := save3Reg }
        is("h40".U) { io.readData := tidReg }
        is("h41".U) { io.readData := tcfgReg }
        is("h42".U) { io.readData := timer_cnt }
    }
    io.eentryOut := Cat(eentry_va, 0.U(6.W))
    io.eraOut    := eraReg
}
```


#### Short summary: 

empty definition using pc, found symbol in pc: 