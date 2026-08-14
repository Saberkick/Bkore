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
    val PGDL        = "h19".U(14.W)
    val PGDH        = "h1a".U(14.W)
    val PGD         = "h1b".U(14.W)
    val SAVE0       = "h30".U(14.W)
    val SAVE1       = "h31".U(14.W)
    val SAVE2       = "h32".U(14.W)
    val SAVE3       = "h33".U(14.W)
    val TID         = "h40".U(14.W)
    val TCFG        = "h41".U(14.W)
    val TVAL        = "h42".U(14.W)
    val TICLR       = "h44".U(14.W)
    val LLBCTL      = "h60".U(14.W)
    val TLBRENTRY   = "h88".U(14.W)
    val DMW0        = "h180".U(14.W)
    val DMW1        = "h181".U(14.W)
}

object ExcCode {
    val INT  = "h00".U(6.W) //Interrupt
    val PIL  = "h01".U(6.W) //Page Invalid (Load)           (V == 0), In MEM
    val PIS  = "h02".U(6.W) //Page Invalid (Store)          (V == 0), In MEM
    val PIF  = "h03".U(6.W) //Page Invalid (Fetch)          (V == 0), In IF
    val PME  = "h04".U(6.W) //Page Modified Exception       (D == 0)
    val PPI  = "h07".U(6.W) //Page Privilege Violation
    val ADEF = "h08".U(6.W) //Address Error (Fetch)         In IF
    val ALE  = "h09".U(6.W) //Address Alignment Error       In MEM
    val IPE  = "h0E".U(6.W) //Instruction Privilege Error
    val TLBR = "h3F".U(6.W) //TLB Refill                    TLB doesn't have this entry

    def isMmuOrAlign(code: UInt): Bool = {
        val codes = Seq(PIL, PIS, PIF, PME, PPI, ADEF, ALE, TLBR)
        codes.map(_ === code).reduce(_ || _)
    }

    /** Exceptions that architecturally provide a VPPN to the TLB handler. */
    def writesTlbehi(code: UInt): Bool = {
        val codes = Seq(PIL, PIS, PIF, PME, PPI, TLBR)
        codes.map(_ === code).reduce(_ || _)
    }
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
    val padding2    = UInt(19.W) //23:5     Reserved
    val index       = UInt(5.W)  //4:0      TLB index (32 entries)
}

//TLB Entry High
class TlbehiReg extends Bundle {
    val vppn        = UInt(19.W) //31:13    Virtual Page Number
    val padding     = UInt(13.W) //12:0     Reserved
}

//TLB Entry Low (We have two)
class TlbeloReg extends Bundle {
    val paddingTop  = UInt(4.W)   //31:28   Reserved for PALEN=32
    val ppn         = UInt(20.W)  //27:8    Physical Page Number
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
    override def desiredName: String = "MyCpuCSR"

    val io = IO(new Bundle {
        val addr        = Input(UInt(14.W))
        val readData    = Output(UInt(32.W))
        
        val writeEn     = Input(Bool())
        val writeData   = Input(UInt(32.W))
        val writeMask   = Input(UInt(32.W))
        
        val eentryOut   = Output(UInt(32.W))
        val eraOut      = Output(UInt(32.W))
        val hasInt      = Output(Bool())
        val interruptPending = Output(UInt(32.W))
        
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
        val tlbidx_out   = Output(UInt(5.W))
        val tlbrentryOut = Output(UInt(32.W))

        val llbitSet   = Input(Bool())
        val llbitClear = Input(Bool())
        val llbit      = Output(Bool())
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
    // LA32 page-directory bases.  PGD itself is a read-only selector: BADV's
    // high bit chooses PGDH for the upper half and PGDL for the lower half.
    val pgdlBase    = RegInit(0.U(20.W))    //31:12
    val pgdhBase    = RegInit(0.U(20.W))    //31:12
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
    val llbit = RegInit(false.B)
    val llbKlo = RegInit(false.B)


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
    io.interruptPending := ecfg.asUInt & estat_wire.asUInt

    // Chiplab samples CSR state on the same positive edge as the commit
    // packet.  Build a post-retirement view here; directly exporting the
    // registers would expose their pre-write values to DPI for one cycle.
    val dtCrmd = WireDefault(Mux(io.writeEn && io.addr === CsrAddr.CRMD,
        maskedWrite(crmd.asUInt, io.writeData, io.writeMask), crmd.asUInt).asTypeOf(new CrmdReg()))
    val dtPrmd = WireDefault(Mux(io.writeEn && io.addr === CsrAddr.PRMD,
        maskedWrite(prmd.asUInt, io.writeData, io.writeMask), prmd.asUInt).asTypeOf(new PrmdReg()))
    val dtEcfg = WireDefault(Mux(io.writeEn && io.addr === CsrAddr.ECFG,
        maskedWrite(ecfg.asUInt, io.writeData, io.writeMask), ecfg.asUInt).asTypeOf(new EcfgReg()))
    val dtEstatSw = WireDefault(Mux(io.writeEn && io.addr === CsrAddr.ESTAT,
        maskedWrite(estat_is_sw, io.writeData(1, 0), io.writeMask(1, 0)), estat_is_sw))
    val dtEstatTimer = WireDefault(estat_is_timer)
    val dtEstatEcode = WireDefault(estat_ecode)
    val dtEstatEsubcode = WireDefault(estat_esubcode)
    val dtTlbidx = WireDefault(Mux(io.writeEn && io.addr === CsrAddr.TLBIDX,
        maskedWrite(tlbidx.asUInt, io.writeData, io.writeMask), tlbidx.asUInt).asTypeOf(new TlbidxReg()))
    val dtTlbehi = WireDefault(Mux(io.writeEn && io.addr === CsrAddr.TLBEHI,
        maskedWrite(tlbehi.asUInt, io.writeData, io.writeMask), tlbehi.asUInt).asTypeOf(new TlbehiReg()))
    val dtTlbelo0 = WireDefault(Mux(io.writeEn && io.addr === CsrAddr.TLBELO0,
        maskedWrite(tlbelo0.asUInt, io.writeData, io.writeMask), tlbelo0.asUInt).asTypeOf(new TlbeloReg()))
    val dtTlbelo1 = WireDefault(Mux(io.writeEn && io.addr === CsrAddr.TLBELO1,
        maskedWrite(tlbelo1.asUInt, io.writeData, io.writeMask), tlbelo1.asUInt).asTypeOf(new TlbeloReg()))
    val dtAsid = WireDefault(Mux(io.writeEn && io.addr === CsrAddr.ASID,
        maskedWrite(asid.asUInt, io.writeData, io.writeMask), asid.asUInt).asTypeOf(new AsidReg()))
    val dtEra = WireDefault(Mux(io.writeEn && io.addr === CsrAddr.ERA,
        maskedWrite(eraReg, io.writeData, io.writeMask), eraReg))
    val dtBadv = WireDefault(Mux(io.writeEn && io.addr === CsrAddr.BADV,
        maskedWrite(badvReg, io.writeData, io.writeMask), badvReg))
    val dtPgdl = WireDefault(Mux(io.writeEn && io.addr === CsrAddr.PGDL,
        maskedWrite(pgdlBase, io.writeData(31, 12), io.writeMask(31, 12)), pgdlBase))
    val dtPgdh = WireDefault(Mux(io.writeEn && io.addr === CsrAddr.PGDH,
        maskedWrite(pgdhBase, io.writeData(31, 12), io.writeMask(31, 12)), pgdhBase))
    val dtEentry = WireDefault(Mux(io.writeEn && io.addr === CsrAddr.EENTRY,
        maskedWrite(eentry_va, io.writeData(31, 6), io.writeMask(31, 6)), eentry_va))
    val dtSave0 = WireDefault(Mux(io.writeEn && io.addr === CsrAddr.SAVE0,
        maskedWrite(save0Reg, io.writeData, io.writeMask), save0Reg))
    val dtSave1 = WireDefault(Mux(io.writeEn && io.addr === CsrAddr.SAVE1,
        maskedWrite(save1Reg, io.writeData, io.writeMask), save1Reg))
    val dtSave2 = WireDefault(Mux(io.writeEn && io.addr === CsrAddr.SAVE2,
        maskedWrite(save2Reg, io.writeData, io.writeMask), save2Reg))
    val dtSave3 = WireDefault(Mux(io.writeEn && io.addr === CsrAddr.SAVE3,
        maskedWrite(save3Reg, io.writeData, io.writeMask), save3Reg))
    val dtTid = WireDefault(Mux(io.writeEn && io.addr === CsrAddr.TID,
        maskedWrite(tidReg, io.writeData, io.writeMask), tidReg))
    val dtTcfg = WireDefault(Mux(io.writeEn && io.addr === CsrAddr.TCFG,
        maskedWrite(tcfg.asUInt, io.writeData, io.writeMask), tcfg.asUInt).asTypeOf(new TcfgReg()))
    val dtTimerCnt = WireDefault(timer_cnt)
    val dtTlbrentry = WireDefault(Mux(io.writeEn && io.addr === CsrAddr.TLBRENTRY,
        maskedWrite(tlbrentry_va, io.writeData(31, 6), io.writeMask(31, 6)), tlbrentry_va))
    val dtDmw0 = WireDefault(Mux(io.writeEn && io.addr === CsrAddr.DMW0,
        maskedWrite(dmw0.asUInt, io.writeData, io.writeMask), dmw0.asUInt).asTypeOf(new DmwReg()))
    val dtDmw1 = WireDefault(Mux(io.writeEn && io.addr === CsrAddr.DMW1,
        maskedWrite(dmw1.asUInt, io.writeData, io.writeMask), dmw1.asUInt).asTypeOf(new DmwReg()))
    val dtLlbit = WireDefault(llbit)
    val dtLlbKlo = WireDefault(llbKlo)

    when(io.writeEn && io.addr === CsrAddr.TICLR && io.writeMask(0) && io.writeData(0)) {
        dtEstatTimer := 0.U
    }
    when(io.writeEn && io.addr === CsrAddr.LLBCTL) {
        when(io.writeMask(2)) { dtLlbKlo := io.writeData(2) }
        when(io.writeMask(1) && io.writeData(1)) { dtLlbit := false.B }
    }

    when(io.writeEn && io.addr === CsrAddr.TCFG) {
        // A TCFG write has priority over counting with the old enable bit.
        // This core uses all ones as the stopped one-shot value, so disabling
        // the timer must enter that state instead of consuming one more tick.
        dtTimerCnt := Mux(dtTcfg.en === 1.U,
            Cat(dtTcfg.initval, 0.U(2.W)), "hffffffff".U)
    }.elsewhen(tcfg.en === 1.U && timer_cnt =/= "hffffffff".U) {
        when(timer_cnt === 0.U) {
            dtEstatTimer := 1.U
            dtTimerCnt := Mux(tcfg.periodic === 1.U,
                Cat(tcfg.initval, 0.U(2.W)), "hffffffff".U)
        }.otherwise {
            dtTimerCnt := timer_cnt - 1.U
        }
    }

    when(io.excValid) {
        dtPrmd.pplv := crmd.plv
        dtPrmd.pie := crmd.ie
        dtCrmd.plv := 0.U
        dtCrmd.ie := 0.U
        when(io.excEcode === ExcCode.TLBR) {
            dtCrmd.da := 1.U
            dtCrmd.pg := 0.U
        }
        dtEra := io.excPc
        dtEstatEcode := io.excEcode
        dtEstatEsubcode := io.excEsubcode
        when(ExcCode.isMmuOrAlign(io.excEcode)) {
            dtBadv := io.excAddr
        }
        when(ExcCode.writesTlbehi(io.excEcode)) {
            dtTlbehi.vppn := io.excAddr(31, 13)
        }
    }.elsewhen(io.ertnFlush) {
        dtCrmd.plv := prmd.pplv
        dtCrmd.ie := prmd.pie
        when(estat_ecode === ExcCode.TLBR) {
            dtCrmd.da := 0.U
            dtCrmd.pg := 1.U
        }
        when(!llbKlo) { dtLlbit := false.B }
        dtLlbKlo := false.B
    }
    when(io.llbitSet) {
        dtLlbit := true.B
    }.elsewhen(io.llbitClear) {
        dtLlbit := false.B
    }

    // TLBRD is architecturally visible at retirement and has the same final
    // priority as the register updates below.
    when(io.tlbrd_we) {
        dtTlbidx.ne := !io.tlbrd_in.e
        when(io.tlbrd_in.e) {
            dtTlbidx.ps := Mux(io.tlbrd_in.ps4MB, 21.U, 12.U)
            dtTlbehi.vppn := io.tlbrd_in.vppn
            dtAsid.asid := io.tlbrd_in.asid
            dtTlbelo0 := io.tlbrd_in.lo0
            dtTlbelo1 := io.tlbrd_in.lo1
        }.otherwise {
            dtTlbidx.ps := 0.U
            dtTlbehi.vppn := 0.U
            dtAsid.asid := 0.U
            dtTlbelo0 := 0.U.asTypeOf(new TlbeloReg())
            dtTlbelo1 := 0.U.asTypeOf(new TlbeloReg())
        }
    }

    dtCrmd.padding := 0.U
    dtPrmd.padding := 0.U
    dtEcfg.padding1 := 0.U
    dtEcfg.padding2 := 0.U
    dtTlbidx.padding := 0.U
    dtTlbidx.padding2 := 0.U
    dtTlbehi.padding := 0.U
    dtTlbelo0.paddingTop := 0.U
    dtTlbelo0.padding := 0.U
    dtTlbelo1.paddingTop := 0.U
    dtTlbelo1.padding := 0.U
    dtAsid.padding1 := 0.U
    dtAsid.padding2 := 0.U
    dtAsid.asidbits := 10.U
    dtDmw0.padding1 := 0.U
    dtDmw0.padding2 := 0.U
    dtDmw0.padding3 := 0.U
    dtDmw1.padding1 := 0.U
    dtDmw1.padding2 := 0.U
    dtDmw1.padding3 := 0.U

    val dtEstat = WireDefault(estat_wire)
    dtEstat.is_sw := dtEstatSw
    dtEstat.is_timer := dtEstatTimer
    dtEstat.ecode := dtEstatEcode
    dtEstat.esubcode := dtEstatEsubcode

    // The adapter instantiates the real DPI module only with DIFFTEST_EN.
    val difftestCsr = Module(new DifftestCSRRegStateSim())
    difftestCsr.clock := clock
    difftestCsr.coreid := 0.U
    difftestCsr.crmd := dtCrmd.asUInt.pad(64)
    difftestCsr.prmd := dtPrmd.asUInt.pad(64)
    difftestCsr.euen := 0.U
    difftestCsr.ecfg := dtEcfg.asUInt.pad(64)
    difftestCsr.estat := dtEstat.asUInt.pad(64)
    difftestCsr.era := dtEra.pad(64)
    difftestCsr.badv := dtBadv.pad(64)
    difftestCsr.eentry := Cat(dtEentry, 0.U(6.W)).pad(64)
    difftestCsr.tlbidx := dtTlbidx.asUInt.pad(64)
    difftestCsr.tlbehi := dtTlbehi.asUInt.pad(64)
    difftestCsr.tlbelo0 := dtTlbelo0.asUInt.pad(64)
    difftestCsr.tlbelo1 := dtTlbelo1.asUInt.pad(64)
    difftestCsr.asid := dtAsid.asUInt.pad(64)
    difftestCsr.pgdl := Cat(dtPgdl, 0.U(12.W)).pad(64)
    difftestCsr.pgdh := Cat(dtPgdh, 0.U(12.W)).pad(64)
    difftestCsr.save0 := dtSave0.pad(64)
    difftestCsr.save1 := dtSave1.pad(64)
    difftestCsr.save2 := dtSave2.pad(64)
    difftestCsr.save3 := dtSave3.pad(64)
    difftestCsr.tid := dtTid.pad(64)
    difftestCsr.tcfg := dtTcfg.asUInt.pad(64)
    difftestCsr.tval := dtTimerCnt.pad(64)
    difftestCsr.ticlr := 0.U
    difftestCsr.llbctl := Cat(0.U(29.W), dtLlbKlo, 0.U(1.W), dtLlbit).pad(64)
    difftestCsr.tlbrentry := Cat(dtTlbrentry, 0.U(6.W)).pad(64)
    difftestCsr.dmw0 := dtDmw0.asUInt.pad(64)
    difftestCsr.dmw1 := dtDmw1.asUInt.pad(64)


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
            is(CsrAddr.PGDL)    { pgdlBase      := maskedWrite(pgdlBase,        io.writeData(31, 12), io.writeMask(31, 12)) }
            is(CsrAddr.PGDH)    { pgdhBase      := maskedWrite(pgdhBase,        io.writeData(31, 12), io.writeMask(31, 12)) }

            is(CsrAddr.SAVE0)   { save0Reg      := maskedWrite(save0Reg,        io.writeData, io.writeMask) }
            is(CsrAddr.SAVE1)   { save1Reg      := maskedWrite(save1Reg,        io.writeData, io.writeMask) }
            is(CsrAddr.SAVE2)   { save2Reg      := maskedWrite(save2Reg,        io.writeData, io.writeMask) }
            is(CsrAddr.SAVE3)   { save3Reg      := maskedWrite(save3Reg,        io.writeData, io.writeMask) }
            is(CsrAddr.TID)     { tidReg        := maskedWrite(tidReg,          io.writeData, io.writeMask) }
            is(CsrAddr.TCFG)    { tcfg          := maskedWrite(tcfg.asUInt,     io.writeData, io.writeMask).asTypeOf(new TcfgReg()) }

            is(CsrAddr.TICLR)   { when((io.writeMask(0) & io.writeData(0)) === 1.U) { estat_is_timer := 0.U } }
            is(CsrAddr.LLBCTL)  {
                when(io.writeMask(2)) { llbKlo := io.writeData(2) }
                when(io.writeMask(1) && io.writeData(1)) { llbit := false.B }
            }

            is(CsrAddr.TLBRENTRY){tlbrentry_va  := maskedWrite(tlbrentry_va,    io.writeData(31, 6), io.writeMask(31, 6)) }
            
            is(CsrAddr.DMW0)    { dmw0          := maskedWrite(dmw0.asUInt,     io.writeData, io.writeMask).asTypeOf(new DmwReg()) }
            is(CsrAddr.DMW1)    { dmw1          := maskedWrite(dmw1.asUInt,     io.writeData, io.writeMask).asTypeOf(new DmwReg()) }
        }
    }


    ////////////////////////////////////////////////////////////////////////
    //Timer Logic
    ////////////////////////////////////////////////////////////////////////
    val tcfg_next_value = maskedWrite(tcfg.asUInt, io.writeData, io.writeMask).asTypeOf(new TcfgReg())
    val is_writing_tcfg = io.writeEn && (io.addr === CsrAddr.TCFG)
    when(is_writing_tcfg) {
        timer_cnt := Mux(tcfg_next_value.en === 1.U,
            Cat(tcfg_next_value.initval, 0.U(2.W)), "hffffffff".U)
    } .elsewhen(tcfg.en === 1.U && timer_cnt =/= "hffffffff".U) {
        when(timer_cnt === 0.U) {
            estat_is_timer := 1.U
            timer_cnt := Mux(tcfg.periodic === 1.U, Cat(tcfg.initval, 0.U(2.W)), "hffffffff".U(32.W))
        } .otherwise { timer_cnt := timer_cnt - 1.U }
    }


    ////////////////////////////////////////////////////////////////////////
    //Hardware Write CSR
    ////////////////////////////////////////////////////////////////////////
    when(io.excValid) {
        prmd.pplv       := crmd.plv
        prmd.pie        := crmd.ie
        crmd.plv        := 0.U
        crmd.ie         := 0.U

        when(io.excEcode === ExcCode.TLBR) {
            crmd.da := 1.U
            crmd.pg := 0.U
        }

        eraReg          := io.excPc
        estat_ecode     := io.excEcode
        estat_esubcode  := io.excEsubcode

        when(ExcCode.isMmuOrAlign(io.excEcode)) {
            badvReg     := io.excAddr
        }
        when(ExcCode.writesTlbehi(io.excEcode)) {
            tlbehi.vppn := io.excAddr(31, 13)
        }
    } .elsewhen(io.ertnFlush) {
        crmd.plv        := prmd.pplv
        crmd.ie         := prmd.pie
        when(estat_ecode === ExcCode.TLBR) {
            crmd.da := 0.U
            crmd.pg := 1.U
        }
        when(!llbKlo) { llbit := false.B }
        llbKlo := false.B
    }

    when(io.llbitSet) {
        llbit := true.B
    }.elsewhen(io.llbitClear) {
        llbit := false.B
    }
    io.llbit := llbit


    ////////////////////////////////////////////////////////////////////////
    //Read Logic
    ////////////////////////////////////////////////////////////////////////
    io.readData := 0.U 
    switch(io.addr) {
        is(CsrAddr.CRMD)    { io.readData := crmd.asUInt }
        is(CsrAddr.PRMD)    { io.readData := prmd.asUInt }
        is(CsrAddr.ECFG)    { io.readData := ecfg.asUInt }
        is(CsrAddr.ESTAT)   { io.readData := estat_wire.asUInt }
        is(CsrAddr.ERA)     { io.readData := eraReg }
        is(CsrAddr.BADV)    { io.readData := badvReg }
        is(CsrAddr.EENTRY)  { io.readData := Cat(eentry_va, 0.U(6.W)) }
        is(CsrAddr.TLBIDX)  { io.readData := tlbidx.asUInt }
        is(CsrAddr.TLBEHI)  { io.readData := tlbehi.asUInt }
        is(CsrAddr.TLBELO0) { io.readData := tlbelo0.asUInt }
        is(CsrAddr.TLBELO1) { io.readData := tlbelo1.asUInt }
        is(CsrAddr.ASID)    { io.readData := asid.asUInt }
        is(CsrAddr.PGDL)    { io.readData := Cat(pgdlBase, 0.U(12.W)) }
        is(CsrAddr.PGDH)    { io.readData := Cat(pgdhBase, 0.U(12.W)) }
        is(CsrAddr.PGD)     { io.readData := Cat(Mux(badvReg(31), pgdhBase, pgdlBase), 0.U(12.W)) }
        is(CsrAddr.SAVE0)   { io.readData := save0Reg }
        is(CsrAddr.SAVE1)   { io.readData := save1Reg }
        is(CsrAddr.SAVE2)   { io.readData := save2Reg }
        is(CsrAddr.SAVE3)   { io.readData := save3Reg }
        is(CsrAddr.TID)     { io.readData := tidReg }
        is(CsrAddr.TCFG)    { io.readData := tcfg.asUInt }
        is(CsrAddr.TVAL)    { io.readData := timer_cnt }
        is(CsrAddr.LLBCTL)  { io.readData := Cat(0.U(29.W), llbKlo, 0.U(1.W), llbit) }
        is(CsrAddr.TLBRENTRY){io.readData := Cat(tlbrentry_va, 0.U(6.W)) }
        is(CsrAddr.DMW0)    { io.readData := dmw0.asUInt }
        is(CsrAddr.DMW1)    { io.readData := dmw1.asUInt }
    }
    io.eentryOut := Cat(eentry_va, 0.U(6.W))
    io.eraOut    := eraReg

    
    ////////////////////////////////////////////////////////////////////////
    //MMU & TLB Connection
    ////////////////////////////////////////////////////////////////////////
    io.mmu_config.crmd   := crmd
    io.mmu_config.asid   := asid
    io.mmu_config.dmw0   := dmw0
    io.mmu_config.dmw1   := dmw1
    io.mmu_config.tlbehi := tlbehi

    io.tlbidx_out   := tlbidx.index
    io.tlbrentryOut := Cat(tlbrentry_va, 0.U(6.W))

    io.tlb_out.e     := Mux(estat_ecode === ExcCode.TLBR, true.B, tlbidx.ne === 0.U)
    io.tlb_out.ps4MB := tlbidx.ps === 21.U
    io.tlb_out.vppn  := tlbehi.vppn
    io.tlb_out.asid  := asid.asid
    io.tlb_out.g     := (tlbelo0.g & tlbelo1.g) === 1.U
    
    io.tlb_out.lo0   := tlbelo0
    io.tlb_out.lo1   := tlbelo1

    when(io.tlbrd_we) {
        tlbidx.ne := !io.tlbrd_in.e
        when(io.tlbrd_in.e) {
            tlbidx.ps   := Mux(io.tlbrd_in.ps4MB, 21.U(6.W), 12.U(6.W))
            tlbehi.vppn := io.tlbrd_in.vppn
            asid.asid   := io.tlbrd_in.asid
            
            tlbelo0     := io.tlbrd_in.lo0
            tlbelo1     := io.tlbrd_in.lo1
        } .otherwise {
            tlbidx.ps   := 0.U
            tlbehi.vppn := 0.U
            asid.asid   := 0.U
            tlbelo0     := 0.U.asTypeOf(new TlbeloReg())
            tlbelo1     := 0.U.asTypeOf(new TlbeloReg())
        }
    }


    ////////////////////////////////////////////////////////////////////////
    //Reserved
    ////////////////////////////////////////////////////////////////////////
    crmd.padding    := 0.U
    prmd.padding    := 0.U
    
    ecfg.padding1   := 0.U
    ecfg.padding2   := 0.U
    
    tlbidx.padding  := 0.U
    tlbidx.padding2 := 0.U
    tlbehi.padding  := 0.U
    tlbelo0.paddingTop := 0.U
    tlbelo0.padding := 0.U
    tlbelo1.paddingTop := 0.U
    tlbelo1.padding := 0.U
    
    asid.padding1   := 0.U
    asid.padding2   := 0.U
    asid.asidbits   := 10.U
    
    dmw0.padding1   := 0.U
    dmw0.padding2   := 0.U
    dmw0.padding3   := 0.U
    dmw1.padding1   := 0.U
    dmw1.padding2   := 0.U
    dmw1.padding3   := 0.U
}


class StableCounter extends Module {
    val io = IO(new Bundle {
        val timer_out = Output(UInt(64.W))
    })
    val counter = RegInit(0.U(64.W))
    counter := counter + 1.U
    io.timer_out := counter
}
