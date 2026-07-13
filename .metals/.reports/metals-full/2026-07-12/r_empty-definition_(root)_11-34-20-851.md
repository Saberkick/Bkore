error id: file://<WORKSPACE>/src/main/scala/mycpu/CSR.scala:
file://<WORKSPACE>/src/main/scala/mycpu/CSR.scala
empty definition using pc, found symbol in pc: 
empty definition using semanticdb
empty definition using fallback
non-local guesses:
	 -chisel3/tcfg_initval.
	 -chisel3/tcfg_initval#
	 -chisel3/tcfg_initval().
	 -chisel3/util/tcfg_initval.
	 -chisel3/util/tcfg_initval#
	 -chisel3/util/tcfg_initval().
	 -tcfg_initval.
	 -tcfg_initval#
	 -tcfg_initval().
	 -scala/Predef.tcfg_initval.
	 -scala/Predef.tcfg_initval#
	 -scala/Predef.tcfg_initval().
offset: 12104
uri: file://<WORKSPACE>/src/main/scala/mycpu/CSR.scala
text:
```scala
package mycpu

import chisel3._
import chisel3.util._

object CsrAddr {
    val CRMD        = "h00".U(14.W)
    val PRMD        = "h01".U(14.W)
    val ECFG        = "h04".U(14.W)
    val ESTAT       = "h05".U(14.W)
    val ERA         = "h06".U(14.W)
    val BADV        = "h07".U(14.W)
    val EENTRY      = "h0c".U(14.W)
    val TLBIDX      = "h10".U(14.W)
    val TLBEHI      = "h11".U(14.W)
    val TLBELO0     = "h12".U(14.W)
    val TLBELO1     = "h13".U(14.W)
    val ASID        = "h18".U(14.W)
    val SAVE0       = "h30".U(14.W)
    val SAVE1       = "h31".U(14.W)
    val SAVE2       = "h32".U(14.W)
    val SAVE3       = "h33".U(14.W)
    val TID         = "h40".U(14.W)
    val TCFG        = "h41".U(14.W)
    val TVAL        = "h42".U(14.W)
    val TICLR       = "h44".U(14.W)
    val TLBRENTRY   = "h88".U(14.W)
    val DMW0        = "h180".U(14.W)
    val DMW1        = "h181".U(14.W)
}

//Current Mode
class CrmdReg extends Bundle {
    val padding     = UInt(23.W)  //31:9    Reserved
    val datm        = UInt(2.W)   //8:7     Data Access Type for Memory     00: uncached, 01: cached
    val datf        = UInt(2.W)   //6:5     Data Access Type for Fetch      00: uncached, 01: cached
    val pg          = UInt(1.W)   //4       Enable Paging
    val da          = UInt(1.W)   //3       Enable Direct Address
    val ie          = UInt(1.W)   //2       Intr Enable
    val plv         = UInt(2.W)   //1:0     0: Kernel, 3: User
}

//Previous Mode
class PrmdReg extends Bundle {
    val padding     = UInt(29.W)  //31:3    Reserved
    val pie         = UInt(1.W)   //2       Previous ie
    val pplv        = UInt(2.W)   //1:0     Previous plv
}

//Exception Configuration
class EcfgReg extends Bundle {
    val padding1    = UInt(19.W) //31:13    Reserved
    val lie_ipi     = UInt(1.W)  //12       Inter-Processor Interrupt
    val lie_timer   = UInt(1.W)  //11       Timer Interrupt
    val padding2    = UInt(1.W)  //10       Reserved
    val lie_hw      = UInt(8.W)  //9:2      Hardware Interrupt
    val lie_sw      = UInt(2.W)  //1:0      Software Interrupt
}

//Exception Status
class EstatReg extends Bundle {
    val padding1    = UInt(1.W)  //31       Reserved
    val esubcode    = UInt(9.W)  //30:22    subcode
    val ecode       = UInt(6.W)  //21:16    0x00 INT, 0x01 PIL, 0x02 PIS, 0x03 PIF, 0x04 PME, 0x07 PPI, 0x08 ADEF, 0x09 ALE, 0x3F TLBR 等)
    val padding2    = UInt(3.W)  //15:13    Reserved
    val is_ipi      = UInt(1.W)  //12       Inter-Processor Interrupt
    val is_timer    = UInt(1.W)  //11       Timer Interrupt
    val padding3    = UInt(1.W)  //10       Reserved
    val is_hw       = UInt(8.W)  //9:2      Hardware Interrupt
    val is_sw       = UInt(2.W)  //1:0      Software Interrupt
}

//TLB Index
class TlbidxReg extends Bundle {
    val ne          = UInt(1.W)  //31       No Entry    1: TLB missed, 0: TLB hit
    val padding     = UInt(1.W)  //30       Reserved
    val ps          = UInt(6.W)  //29:24    Page size   12: 4KB, 21: 2MB
    val padding2    = UInt(20.W) //23:4     Reserved
    val index       = UInt(4.W)  //3:0      TLB index
}

//TLB Entry High
class TlbehiReg extends Bundle {
    val vppn        = UInt(19.W) //31:13    Virtual Page Number
    val padding     = UInt(13.W) //12:0     Reserved
}

//TLB Entry Low (We have two)
class TlbeloReg extends Bundle {
    val ppn         = UInt(24.W)  //31:8    Physical Page Number
    val padding     = UInt(1.W)   //7       Reserved
    val g           = UInt(1.W)   //6       Global, if 1 disable ASID matching
    val mat         = UInt(2.W)   //5:4     Memory Access Type  00: uncached, 01: cached
    val plv         = UInt(2.W)   //3:2     Privilege Level
    val d           = UInt(1.W)   //1       Dirty
    val v           = UInt(1.W)   //0       Valid
}

//Address Space Identifier
//  To distinguish different process's address space
class AsidReg extends Bundle {
    val padding1    = UInt(8.W)   //31:24   Reserved
    val asidbits    = UInt(8.W)   //23:16   Fixed to 10
    val padding2    = UInt(6.W)   //15:10   Reserved
    val asid        = UInt(10.W)  //9:0     Process ID
}

//Direct Mapping Window
//  A segment is 4GB / 8 = 512MB
class DmwReg extends Bundle {
    val vseg        = UInt(3.W)   //31:29   Virtual Segment
    val padding1    = UInt(1.W)   //28      Reserved
    val pseg        = UInt(3.W)   //27:25   Physical Segment
    val padding2    = UInt(19.W)  //24:6    Reserved
    val mat         = UInt(2.W)   //5:4     Memory Access Type  00: uncached, 01: cached
    val plv3        = UInt(1.W)   //3       Enable User State to access
    val padding3    = UInt(2.W)   //2:1     Reserved
    val plv0        = UInt(1.W)   //0       Enable Kernel State to access
}

//Timer Config
class TcfgReg extends Bundle {
    val initval     = UInt(30.W)  //31:2    [Initval, 00]
    val periodic    = UInt(1.W)   //1       Periodic or Single
    val en          = UInt(1.W)   //0       en
}


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

        val mmu_config = Output(new MmuConfig())

        val tlbrd_we     = Input(Bool())
        val tlbrd_in     = Input(new TlbEntry())
        val tlb_out      = Output(new TlbEntry())
        val tlbidx_out   = Output(UInt(4.W))
        val tlbrentryOut = Output(UInt(32.W))
    })

    def maskedWrite(reg: UInt, wdata: UInt, wmask: UInt): UInt = { (reg & ~wmask) | (wdata & wmask)}

    ////////////////////////////////////////////////////////////////////////
    //Reginit
    ////////////////////////////////////////////////////////////////////////
    val crmd = RegInit({
        val init = WireDefault(0.U.asTypeOf(new CrmdReg()))
        init.da := 1.U //Direct Address
        init
    })
    val prmd = RegInit(0.U.asTypeOf(new PrmdReg()))
    val ecfg = RegInit(0.U.asTypeOf(new EcfgReg()))
    val estat_is_sw    = RegInit(0.U(2.W))
    val estat_is_timer = RegInit(0.U(1.W))
    val estat_ecode    = RegInit(0.U(6.W))
    val estat_esubcode = RegInit(0.U(9.W))
    val tlbidx  = RegInit(0.U.asTypeOf(new TlbidxReg()))
    val tlbehi  = RegInit(0.U.asTypeOf(new TlbehiReg()))
    val tlbelo0 = RegInit(0.U.asTypeOf(new TlbeloReg()))
    val tlbelo1 = RegInit(0.U.asTypeOf(new TlbeloReg()))
    val asid = RegInit({
        val init = WireDefault(0.U.asTypeOf(new AsidReg()))
        init.asidbits := 10.U //Fixed to 10
        init
    })
    val dmw0 = RegInit(0.U.asTypeOf(new DmwReg()))
    val dmw1 = RegInit(0.U.asTypeOf(new DmwReg()))
    val tcfg = RegInit(0.U.asTypeOf(new TcfgReg()))

    //Exception Return Address
    val eraReg      = RegInit(0.U(32.W))
    //Bad Virtual Address
    val badvReg     = RegInit(0.U(32.W))
    //Exception Entry
    val eentry_va   = RegInit(0.U(26.W))    //31:6
    //Save Registers
    val save0Reg    = RegInit(0.U(32.W))
    val save1Reg    = RegInit(0.U(32.W))
    val save2Reg    = RegInit(0.U(32.W))
    val save3Reg    = RegInit(0.U(32.W))
    //Timer ID
    val tidReg      = RegInit(0.U(32.W))
    val timer_cnt   = RegInit("hffffffff".U(32.W))
    //TLB Refill Exception Entry Register
    //Virtual Address for TLB Refill Exception Handler
    val tlbrentry_va = RegInit(0.U(26.W))   //31:6


    ////////////////////////////////////////////////////////////////////////
    //Interrupt Logic
    ////////////////////////////////////////////////////////////////////////
    val estat_wire = Wire(new EstatReg())
    estat_wire.padding1 := 0.U
    estat_wire.esubcode := estat_esubcode
    estat_wire.ecode    := estat_ecode
    estat_wire.padding2 := 0.U
    estat_wire.is_ipi   := 0.U
    estat_wire.is_timer := estat_is_timer
    estat_wire.padding3 := 0.U
    estat_wire.is_hw    := io.hw_int_in
    estat_wire.is_sw    := estat_is_sw
    io.hasInt := ((ecfg.asUInt & estat_wire.asUInt) =/= 0.U) && (crmd.ie === 1.U)


    ////////////////////////////////////////////////////////////////////////
    //Write Logic
    ////////////////////////////////////////////////////////////////////////
    when(io.writeEn) {
        switch(io.addr) {
            is(CsrAddr.CRMD)    { crmd          := maskedWrite(crmd.asUInt,     io.writeData, io.writeMask).asTypeOf(new CrmdReg()) }
            is(CsrAddr.PRMD)    { prmd          := maskedWrite(prmd.asUInt,     io.writeData, io.writeMask).asTypeOf(new PrmdReg()) }
            is(CsrAddr.ECFG)    { ecfg          := maskedWrite(ecfg.asUInt,     io.writeData, io.writeMask).asTypeOf(new EcfgReg()) }
            is(CsrAddr.ESTAT)   { estat_is_sw   := maskedWrite(estat_is_sw,     io.writeData(1, 0), io.writeMask(1, 0)) } 
            
            is(CsrAddr.ERA)     { eraReg        := maskedWrite(eraReg,          io.writeData, io.writeMask) }
            is(CsrAddr.BADV)    { badvReg       := maskedWrite(badvReg,         io.writeData, io.writeMask) }
            is(CsrAddr.EENTRY)  { eentry_va     := maskedWrite(eentry_va,       io.writeData(31, 6), io.writeMask(31, 6)) }

            is(CsrAddr.TLBIDX)  { tlbidx        := maskedWrite(tlbidx.asUInt,   io.writeData, io.writeMask).asTypeOf(new TlbidxReg()) }
            is(CsrAddr.TLBEHI)  { tlbehi        := maskedWrite(tlbehi.asUInt,   io.writeData, io.writeMask).asTypeOf(new TlbehiReg()) }
            is(CsrAddr.TLBELO0) { tlbelo0       := maskedWrite(tlbelo0.asUInt,  io.writeData, io.writeMask).asTypeOf(new TlbeloReg()) }
            is(CsrAddr.TLBELO1) { tlbelo1       := maskedWrite(tlbelo1.asUInt,  io.writeData, io.writeMask).asTypeOf(new TlbeloReg()) }
            is(CsrAddr.ASID)    { asid          := maskedWrite(asid.asUInt,     io.writeData, io.writeMask).asTypeOf(new AsidReg()) }

            is(CsrAddr.SAVE0)   { save0Reg      := maskedWrite(save0Reg,        io.writeData, io.writeMask) }
            is(CsrAddr.SAVE1)   { save1Reg      := maskedWrite(save1Reg,        io.writeData, io.writeMask) }
            is(CsrAddr.SAVE2)   { save2Reg      := maskedWrite(save2Reg,        io.writeData, io.writeMask) }
            is(CsrAddr.SAVE3)   { save3Reg      := maskedWrite(save3Reg,        io.writeData, io.writeMask) }
            is(CsrAddr.TID)     { tidReg        := maskedWrite(tidReg,          io.writeData, io.writeMask) }
            is(CsrAddr.TCFG)    { tcfg          := maskedWrite(tcfg.asUInt,     io.writeData, io.writeMask).asTypeOf(new TcfgReg()) }

            is(CsrAddr.TICLR)   { when((io.writeMask(0) & io.writeData(0)) === 1.U) { estat_is_timer := 0.U } }

            is(CsrAddr.TLBRENTRY){tlbrentry_va  := maskedWrite(tlbrentry_va,    io.writeData(31, 6), io.writeMask(31, 6)) }
            
            is(CsrAddr.DMW0)    { dmw0          := maskedWrite(dmw0.asUInt,     io.writeData, io.writeMask).asTypeOf(new DmwReg()) }
            is(CsrAddr.DMW1)    { dmw1          := maskedWrite(dmw1.asUInt,     io.writeData, io.writeMask).asTypeOf(new DmwReg()) }
        }
    }


    ////////////////////////////////////////////////////////////////////////
    //Timer Logic
    ////////////////////////////////////////////////////////////////////////
    val tcfg_next_value = maskedWrite(tcfgReg, io.writeData, io.writeMask)
    val is_writing_tcfg = io.writeEn && (io.addr === "h41".U)

    when(is_writing_tcfg && tcfg_next_value(0) === 1.U) {
        timer_cnt := Cat(tcfg_next_value(31, 2), 0.U(2.W))
    } .elsewhen(tcfg_en === 1.U && timer_cnt =/= "hffffffff".U) {
        when(timer_cnt === 0.U) {
            estat_is_timer := 1.U
            timer_cnt := Mux(tcfg_periodic === 1.U, Cat(tcfg_@@initval, 0.U(2.W)), "hffffffff".U(32.W))
        } .otherwise {
            timer_cnt := timer_cnt - 1.U
        }
    }

    when(io.excValid) {
        prmd_pplv       := crmd_plv
        prmd_pie        := crmd_ie
        crmd_plv        := 0.U
        crmd_ie         := 0.U
        when(io.excEcode === "h3F".U) {
            crmd_da := 1.U
            crmd_pg := 0.U
        }
        eraReg          := io.excPc
        estat_ecode     := io.excEcode
        estat_esubcode  := io.excEsubcode
        //ADEF(0x8) or ALE(0x9) 
        val is_mmu_or_align_exc = (
            io.excEcode === "h01".U || // PIL:  Load 操作页无效例外
            io.excEcode === "h02".U || // PIS:  Store 操作页无效例外
            io.excEcode === "h03".U || // PIF:  取指操作页无效例外
            io.excEcode === "h04".U || // PME:  页修改例外
            io.excEcode === "h07".U || // PPI:  页特权等级不合规例外
            io.excEcode === "h08".U || // ADEF / ADEM: 取指/访存地址错例外
            io.excEcode === "h09".U || // ALE:  地址非对齐例外
            io.excEcode === "h3F".U    // TLBR: TLB 重填例外
        )
        when(is_mmu_or_align_exc) {
            badvReg     := io.excAddr
            tlbehi_vppn := io.excAddr(31, 13)
        }
    } .elsewhen(io.ertnFlush) {
        crmd_plv        := prmd_pplv
        crmd_ie         := prmd_pie
        when(estat_ecode === "h3F".U) {
            crmd_da := 0.U
            crmd_pg := 1.U
        }
    }

    io.readData := 0.U 
    switch(io.addr) {
        is("h00".U) { io.readData := Cat(0.U(23.W), crmd_datm, crmd_datf, crmd_pg, crmd_da, crmd_ie, crmd_plv) }
        is("h01".U) { io.readData := Cat(0.U(29.W), prmd_pie, prmd_pplv) }
        is("h04".U) { io.readData := Cat(0.U(19.W), ecfg_lie) }
        is("h05".U) { io.readData := Cat(0.U(1.W), estat_esubcode, estat_ecode, 0.U(3.W), estat) }
        is("h06".U) { io.readData := eraReg }
        is("h07".U) { io.readData := badvReg }
        is("h0c".U) { io.readData := Cat(eentry_va, 0.U(6.W)) }
        is("h10".U) { io.readData := Cat(tlbidx_ne, 0.U(1.W), tlbidx_ps, 0.U(20.W), tlbidx_index) }
        is("h11".U) { io.readData := Cat(tlbehi_vppn, 0.U(13.W)) }
        is("h12".U) { io.readData := Cat(tlbelo0_ppn, 0.U(1.W), tlbelo0_g, tlbelo0_mat, tlbelo0_plv, tlbelo0_d, tlbelo0_v) }
        is("h13".U) { io.readData := Cat(tlbelo1_ppn, 0.U(1.W), tlbelo1_g, tlbelo1_mat, tlbelo1_plv, tlbelo1_d, tlbelo1_v) }
        is("h18".U) { io.readData := Cat(0.U(8.W), asid_asidbits, 0.U(6.W), asid_asid) }
        is("h30".U) { io.readData := save0Reg }
        is("h31".U) { io.readData := save1Reg }
        is("h32".U) { io.readData := save2Reg }
        is("h33".U) { io.readData := save3Reg }
        is("h40".U) { io.readData := tidReg }
        is("h41".U) { io.readData := tcfgReg }
        is("h42".U) { io.readData := timer_cnt }
        is("h88".U) { io.readData := Cat(tlbrentry_va, 0.U(6.W)) } ///////
        is("h180".U) { io.readData := Cat(dmw0_vseg, 0.U(1.W), dmw0_pseg, 0.U(19.W), dmw0_mat, dmw0_plv3, 0.U(2.W), dmw0_plv0) }
        is("h181".U) { io.readData := Cat(dmw1_vseg, 0.U(1.W), dmw1_pseg, 0.U(19.W), dmw1_mat, dmw1_plv3, 0.U(2.W), dmw1_plv0) }    
    }
    io.eentryOut := Cat(eentry_va, 0.U(6.W))
    io.eraOut    := eraReg




    io.mmu_config.da        := crmd_da === 1.U
    io.mmu_config.pg        := crmd_pg === 1.U
    //MODDED in Cache
    io.mmu_config.datf      := crmd_datf      // 新增：连上内部寄存器
    io.mmu_config.datm      := crmd_datm      // 新增：连上内部寄存器
    io.mmu_config.plv       := crmd_plv
    io.mmu_config.asid      := asid_asid

    io.mmu_config.dmw0_plv0 := dmw0_plv0 === 1.U
    io.mmu_config.dmw0_plv3 := dmw0_plv3 === 1.U
    io.mmu_config.dmw0_mat  := dmw0_mat
    io.mmu_config.dmw0_pseg := dmw0_pseg
    io.mmu_config.dmw0_vseg := dmw0_vseg

    io.mmu_config.dmw1_plv0 := dmw1_plv0 === 1.U
    io.mmu_config.dmw1_plv3 := dmw1_plv3 === 1.U
    io.mmu_config.dmw1_mat  := dmw1_mat
    io.mmu_config.dmw1_pseg := dmw1_pseg
    io.mmu_config.dmw1_vseg := dmw1_vseg

    io.mmu_config.tlbehi_vppn := tlbehi_vppn

    // ------------- 新增：TLB 读写映射逻辑 -------------
    // 1. 组装给 StageWB 写入 TLB 用的数据
    io.tlbidx_out := tlbidx_index
    io.tlbrentryOut := Cat(tlbrentry_va, 0.U(6.W))

    io.tlb_out.e     := Mux(estat_ecode === "h3F".U, true.B, !tlbidx_ne)//MODDED
    io.tlb_out.ps4MB := tlbidx_ps === 21.U
    io.tlb_out.vppn  := tlbehi_vppn
    io.tlb_out.asid  := asid_asid
    io.tlb_out.g     := tlbelo0_g & tlbelo1_g
    
    io.tlb_out.ppn0  := tlbelo0_ppn
    io.tlb_out.plv0  := tlbelo0_plv
    io.tlb_out.mat0  := tlbelo0_mat
    io.tlb_out.d0    := tlbelo0_d === 1.U
    io.tlb_out.v0    := tlbelo0_v === 1.U

    io.tlb_out.ppn1  := tlbelo1_ppn
    io.tlb_out.plv1  := tlbelo1_plv
    io.tlb_out.mat1  := tlbelo1_mat
    io.tlb_out.d1    := tlbelo1_d === 1.U
    io.tlb_out.v1    := tlbelo1_v === 1.U

    // 2. 处理 tlbrd 指令的批量写入
    when(io.tlbrd_we) {
        tlbidx_ne := !io.tlbrd_in.e
        
        when(io.tlbrd_in.e) {
            // 如果读出的表项有效 (E=1)，正常拷贝数据
            tlbidx_ps   := Mux(io.tlbrd_in.ps4MB, 21.U(6.W), 12.U(6.W))
            tlbehi_vppn := io.tlbrd_in.vppn
            asid_asid   := io.tlbrd_in.asid
            
            tlbelo0_v   := io.tlbrd_in.v0
            tlbelo0_d   := io.tlbrd_in.d0
            tlbelo0_plv := io.tlbrd_in.plv0
            tlbelo0_mat := io.tlbrd_in.mat0
            tlbelo0_g   := io.tlbrd_in.g
            tlbelo0_ppn := io.tlbrd_in.ppn0

            tlbelo1_v   := io.tlbrd_in.v1
            tlbelo1_d   := io.tlbrd_in.d1
            tlbelo1_plv := io.tlbrd_in.plv1
            tlbelo1_mat := io.tlbrd_in.mat1
            tlbelo1_g   := io.tlbrd_in.g
            tlbelo1_ppn := io.tlbrd_in.ppn1
        } .otherwise {
            // 如果读出的表项无效 (E=0)，按照龙芯规范，强制清零其他关联寄存器字段！
            tlbidx_ps   := 0.U
            tlbehi_vppn := 0.U
            asid_asid   := 0.U
            
            tlbelo0_v   := 0.U
            tlbelo0_d   := 0.U
            tlbelo0_plv := 0.U
            tlbelo0_mat := 0.U
            tlbelo0_g   := 0.U
            tlbelo0_ppn := 0.U

            tlbelo1_v   := 0.U
            tlbelo1_d   := 0.U
            tlbelo1_plv := 0.U
            tlbelo1_mat := 0.U
            tlbelo1_g   := 0.U
            tlbelo1_ppn := 0.U
        }
    }
}
```


#### Short summary: 

empty definition using pc, found symbol in pc: 