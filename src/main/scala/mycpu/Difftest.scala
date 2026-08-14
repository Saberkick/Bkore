package mycpu

import chisel3._

/**
  * Simulation-only adapters for chiplab's Verilator DPI modules.
  *
  * The adapters themselves are always present in the emitted RTL, but the
  * actual Difftest modules are instantiated only when chiplab passes
  * `DIFFTEST_EN.  Vivado and standalone Chisel tests therefore see empty,
  * output-less modules and do not acquire a dependency on
  * sims/verilator/testbench/difftest.v.
  */
class DifftestInstrCommitSim extends ExtModule {
    override def desiredName: String = "DifftestInstrCommitSim"

    val clock          = IO(Input(Clock()))
    val coreid         = IO(Input(UInt(8.W)))
    val index          = IO(Input(UInt(8.W)))
    val valid          = IO(Input(Bool()))
    val pc             = IO(Input(UInt(64.W)))
    val instr          = IO(Input(UInt(32.W)))
    val skip           = IO(Input(Bool()))
    val is_TLBFILL     = IO(Input(Bool()))
    val TLBFILL_index  = IO(Input(UInt(5.W)))
    val is_CNTinst     = IO(Input(Bool()))
    val timer_64_value = IO(Input(UInt(64.W)))
    val wen            = IO(Input(Bool()))
    val wdest          = IO(Input(UInt(8.W)))
    val wdata          = IO(Input(UInt(64.W)))
    val csr_rstat      = IO(Input(Bool()))
    val csr_data       = IO(Input(UInt(32.W)))

    setInline("DifftestInstrCommitSim.sv",
        """
          |module DifftestInstrCommitSim(
          |    input         clock,
          |    input  [ 7:0] coreid,
          |    input  [ 7:0] index,
          |    input         valid,
          |    input  [63:0] pc,
          |    input  [31:0] instr,
          |    input         skip,
          |    input         is_TLBFILL,
          |    input  [ 4:0] TLBFILL_index,
          |    input         is_CNTinst,
          |    input  [63:0] timer_64_value,
          |    input         wen,
          |    input  [ 7:0] wdest,
          |    input  [63:0] wdata,
          |    input         csr_rstat,
          |    input  [31:0] csr_data
          |);
          |`ifdef DIFFTEST_EN
          |    DifftestInstrCommit impl (
          |        .clock(clock),
          |        .coreid(coreid),
          |        .index(index),
          |        .valid(valid),
          |        .pc(pc),
          |        .instr(instr),
          |        .skip(skip),
          |        .is_TLBFILL(is_TLBFILL),
          |        .TLBFILL_index(TLBFILL_index),
          |        .is_CNTinst(is_CNTinst),
          |        .timer_64_value(timer_64_value),
          |        .wen(wen),
          |        .wdest(wdest),
          |        .wdata(wdata),
          |        .csr_rstat(csr_rstat),
          |        .csr_data(csr_data)
          |    );
          |`endif
          |endmodule
          |""".stripMargin)
}

class DifftestExcpEventSim extends ExtModule {
    override def desiredName: String = "DifftestExcpEventSim"

    val clock         = IO(Input(Clock()))
    val coreid        = IO(Input(UInt(8.W)))
    val excp_valid    = IO(Input(Bool()))
    val eret          = IO(Input(Bool()))
    val intrNo        = IO(Input(UInt(32.W)))
    val cause         = IO(Input(UInt(32.W)))
    val exceptionPC   = IO(Input(UInt(64.W)))
    val exceptionInst = IO(Input(UInt(32.W)))

    setInline("DifftestExcpEventSim.sv",
        """
          |module DifftestExcpEventSim(
          |    input         clock,
          |    input  [ 7:0] coreid,
          |    input         excp_valid,
          |    input         eret,
          |    input  [31:0] intrNo,
          |    input  [31:0] cause,
          |    input  [63:0] exceptionPC,
          |    input  [31:0] exceptionInst
          |);
          |`ifdef DIFFTEST_EN
          |    DifftestExcpEvent impl (
          |        .clock(clock),
          |        .coreid(coreid),
          |        .excp_valid(excp_valid),
          |        .eret(eret),
          |        .intrNo(intrNo),
          |        .cause(cause),
          |        .exceptionPC(exceptionPC),
          |        .exceptionInst(exceptionInst)
          |    );
          |`endif
          |endmodule
          |""".stripMargin)
}

class DifftestLoadEventSim extends ExtModule {
    override def desiredName: String = "DifftestLoadEventSim"

    val clock  = IO(Input(Clock()))
    val coreid = IO(Input(UInt(8.W)))
    val index  = IO(Input(UInt(8.W)))
    val valid  = IO(Input(UInt(8.W)))
    val paddr  = IO(Input(UInt(64.W)))
    val vaddr  = IO(Input(UInt(64.W)))

    setInline("DifftestLoadEventSim.sv",
        """
          |module DifftestLoadEventSim(
          |    input         clock,
          |    input  [ 7:0] coreid,
          |    input  [ 7:0] index,
          |    input  [ 7:0] valid,
          |    input  [63:0] paddr,
          |    input  [63:0] vaddr
          |);
          |`ifdef DIFFTEST_EN
          |    DifftestLoadEvent impl (
          |        .clock(clock),
          |        .coreid(coreid),
          |        .index(index),
          |        .valid(valid),
          |        .paddr(paddr),
          |        .vaddr(vaddr)
          |    );
          |`endif
          |endmodule
          |""".stripMargin)
}

class DifftestStoreEventSim extends ExtModule {
    override def desiredName: String = "DifftestStoreEventSim"

    val clock      = IO(Input(Clock()))
    val coreid     = IO(Input(UInt(8.W)))
    val index      = IO(Input(UInt(8.W)))
    val valid      = IO(Input(UInt(8.W)))
    val storePAddr = IO(Input(UInt(64.W)))
    val storeVAddr = IO(Input(UInt(64.W)))
    val storeData  = IO(Input(UInt(64.W)))

    setInline("DifftestStoreEventSim.sv",
        """
          |module DifftestStoreEventSim(
          |    input         clock,
          |    input  [ 7:0] coreid,
          |    input  [ 7:0] index,
          |    input  [ 7:0] valid,
          |    input  [63:0] storePAddr,
          |    input  [63:0] storeVAddr,
          |    input  [63:0] storeData
          |);
          |`ifdef DIFFTEST_EN
          |    DifftestStoreEvent impl (
          |        .clock(clock),
          |        .coreid(coreid),
          |        .index(index),
          |        .valid(valid),
          |        .storePAddr(storePAddr),
          |        .storeVAddr(storeVAddr),
          |        .storeData(storeData)
          |    );
          |`endif
          |endmodule
          |""".stripMargin)
}

class DifftestCSRRegStateSim extends ExtModule {
    override def desiredName: String = "DifftestCSRRegStateSim"

    val clock      = IO(Input(Clock()))
    val coreid     = IO(Input(UInt(8.W)))
    val crmd       = IO(Input(UInt(64.W)))
    val prmd       = IO(Input(UInt(64.W)))
    val euen       = IO(Input(UInt(64.W)))
    val ecfg       = IO(Input(UInt(64.W)))
    val estat      = IO(Input(UInt(64.W)))
    val era        = IO(Input(UInt(64.W)))
    val badv       = IO(Input(UInt(64.W)))
    val eentry     = IO(Input(UInt(64.W)))
    val tlbidx     = IO(Input(UInt(64.W)))
    val tlbehi     = IO(Input(UInt(64.W)))
    val tlbelo0    = IO(Input(UInt(64.W)))
    val tlbelo1    = IO(Input(UInt(64.W)))
    val asid       = IO(Input(UInt(64.W)))
    val pgdl       = IO(Input(UInt(64.W)))
    val pgdh       = IO(Input(UInt(64.W)))
    val save0      = IO(Input(UInt(64.W)))
    val save1      = IO(Input(UInt(64.W)))
    val save2      = IO(Input(UInt(64.W)))
    val save3      = IO(Input(UInt(64.W)))
    val tid        = IO(Input(UInt(64.W)))
    val tcfg       = IO(Input(UInt(64.W)))
    val tval       = IO(Input(UInt(64.W)))
    val ticlr      = IO(Input(UInt(64.W)))
    val llbctl     = IO(Input(UInt(64.W)))
    val tlbrentry  = IO(Input(UInt(64.W)))
    val dmw0       = IO(Input(UInt(64.W)))
    val dmw1       = IO(Input(UInt(64.W)))

    setInline("DifftestCSRRegStateSim.sv",
        """
          |module DifftestCSRRegStateSim(
          |    input         clock,
          |    input  [ 7:0] coreid,
          |    input  [63:0] crmd,
          |    input  [63:0] prmd,
          |    input  [63:0] euen,
          |    input  [63:0] ecfg,
          |    input  [63:0] estat,
          |    input  [63:0] era,
          |    input  [63:0] badv,
          |    input  [63:0] eentry,
          |    input  [63:0] tlbidx,
          |    input  [63:0] tlbehi,
          |    input  [63:0] tlbelo0,
          |    input  [63:0] tlbelo1,
          |    input  [63:0] asid,
          |    input  [63:0] pgdl,
          |    input  [63:0] pgdh,
          |    input  [63:0] save0,
          |    input  [63:0] save1,
          |    input  [63:0] save2,
          |    input  [63:0] save3,
          |    input  [63:0] tid,
          |    input  [63:0] tcfg,
          |    input  [63:0] tval,
          |    input  [63:0] ticlr,
          |    input  [63:0] llbctl,
          |    input  [63:0] tlbrentry,
          |    input  [63:0] dmw0,
          |    input  [63:0] dmw1
          |);
          |`ifdef DIFFTEST_EN
          |    DifftestCSRRegState impl (
          |        .clock(clock),
          |        .coreid(coreid),
          |        .crmd(crmd),
          |        .prmd(prmd),
          |        .euen(euen),
          |        .ecfg(ecfg),
          |        .estat(estat),
          |        .era(era),
          |        .badv(badv),
          |        .eentry(eentry),
          |        .tlbidx(tlbidx),
          |        .tlbehi(tlbehi),
          |        .tlbelo0(tlbelo0),
          |        .tlbelo1(tlbelo1),
          |        .asid(asid),
          |        .pgdl(pgdl),
          |        .pgdh(pgdh),
          |        .save0(save0),
          |        .save1(save1),
          |        .save2(save2),
          |        .save3(save3),
          |        .tid(tid),
          |        .tcfg(tcfg),
          |        .tval(tval),
          |        .ticlr(ticlr),
          |        .llbctl(llbctl),
          |        .tlbrentry(tlbrentry),
          |        .dmw0(dmw0),
          |        .dmw1(dmw1)
          |    );
          |`endif
          |endmodule
          |""".stripMargin)
}

/** Complete GPR snapshot required by chiplab's first-commit initialization. */
class DifftestGRegStateSim extends ExtModule {
    override def desiredName: String = "DifftestGRegStateSim"

    val clock  = IO(Input(Clock()))
    val coreid = IO(Input(UInt(8.W)))
    val gpr = Seq.tabulate(32) { index =>
        IO(Input(UInt(64.W))).suggestName(s"gpr_$index")
    }

    private val portDeclarations = (0 until 32)
        .map(index => f"    input  [63:0] gpr_$index%d")
        .mkString(",\n")
    private val portConnections = (0 until 32)
        .map(index => f"        .gpr_$index%d(gpr_$index%d)")
        .mkString(",\n")

    setInline("DifftestGRegStateSim.sv",
        s"""
           |module DifftestGRegStateSim(
           |    input         clock,
           |    input  [ 7:0] coreid,
           |$portDeclarations
           |);
           |`ifdef DIFFTEST_EN
           |    DifftestGRegState impl (
           |        .clock(clock),
           |        .coreid(coreid),
           |$portConnections
           |    );
           |`endif
           |endmodule
           |""".stripMargin)
}
