error id: AF2B14B53C04CFF2F806985D6972B492
file://<WORKSPACE>/src/main/scala/mycpu/StageEX.scala
### scala.ScalaReflectionException: value ready_go is not a method

occurred in the presentation compiler.



action parameters:
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
    val ready_go = Wire(Bool()) /
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
    val mem_req_valid = valid_reg && !data_reg.hasException && !ale && !io.mem_has_exc_in
    //R/W DataMEM
    val stMaskB = "b0001".U(4.W) << alu_res(1, 0)
    val stMaskH = Mux(alu_res(1), "b1100".U(4.W), "b0011".U(4.W))
    val stMaskW = "b1111".U(4.W)
    
    io.data_sram.en   := (data_reg.memWe || data_reg.resFromMem) && mem_req_valid && !io.flush
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


presentation compiler configuration:
Scala version: 2.13.18
Classpath:
<WORKSPACE>/.bloop/root/bloop-bsp-clients-classes/classes-Metals-lEvEGRWbR1GFH5cyjJqv_Q== [exists ], <HOME>/Library/Caches/bloop/semanticdb/com.sourcegraph.semanticdb-javac.0.11.2/semanticdb-javac-0.11.2.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/scala-library/2.13.18/scala-library-2.13.18.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/chipsalliance/chisel_2.13/7.7.0/chisel_2.13-7.7.0.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/github/scopt/scopt_2.13/4.1.0/scopt_2.13-4.1.0.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/apache/commons/commons-text/1.15.0/commons-text-1.15.0.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/os-lib_2.13/0.10.7/os-lib_2.13-0.10.7.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/github/json4s/json4s-native_2.13/4.1.0/json4s-native_2.13-4.1.0.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/github/alexarchambault/data-class_2.13/0.2.7/data-class_2.13-0.2.7.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/scala-reflect/2.13.18/scala-reflect-2.13.18.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/upickle_2.13/3.3.1/upickle_2.13-3.3.1.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/chipsalliance/firtool-resolver_2.13/2.0.1/firtool-resolver_2.13-2.0.1.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/apache/commons/commons-lang3/3.20.0/commons-lang3-3.20.0.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/geny_2.13/1.1.1/geny_2.13-1.1.1.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/github/json4s/json4s-core_2.13/4.1.0/json4s-core_2.13-4.1.0.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/github/json4s/json4s-native-core_2.13/4.1.0/json4s-native-core_2.13-4.1.0.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/ujson_2.13/3.3.1/ujson_2.13-3.3.1.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/upack_2.13/3.3.1/upack_2.13-3.3.1.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/upickle-implicits_2.13/3.3.1/upickle-implicits_2.13-3.3.1.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/modules/scala-xml_2.13/2.2.0/scala-xml_2.13-2.2.0.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/modules/scala-collection-compat_2.13/2.11.0/scala-collection-compat_2.13-2.11.0.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/github/json4s/json4s-ast_2.13/4.1.0/json4s-ast_2.13-4.1.0.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/github/json4s/json4s-scalap_2.13/4.1.0/json4s-scalap_2.13-4.1.0.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/upickle-core_2.13/3.3.1/upickle-core_2.13-3.3.1.jar [exists ]
Options:
-language:reflectiveCalls -deprecation -feature -Xcheckinit -Ymacro-annotations -Yrangepos -Xplugin-require:semanticdb




#### Error stacktrace:

```
scala.reflect.api.Symbols$SymbolApi.asMethod(Symbols.scala:240)
	scala.reflect.api.Symbols$SymbolApi.asMethod$(Symbols.scala:234)
	scala.reflect.internal.Symbols$SymbolContextApiImpl.asMethod(Symbols.scala:101)
	scala.tools.nsc.typechecker.ContextErrors$TyperContextErrors$TyperErrorGen$.MissingArgsForMethodTpeError(ContextErrors.scala:818)
	scala.tools.nsc.typechecker.Typers$Typer.adaptMethodTypeToExpr$1(Typers.scala:909)
	scala.tools.nsc.typechecker.Typers$Typer.adapt(Typers.scala:1266)
	scala.tools.nsc.typechecker.Typers$Typer.typed(Typers.scala:6375)
	scala.tools.nsc.typechecker.Typers$Typer.typedDefDef(Typers.scala:6624)
	scala.tools.nsc.typechecker.Typers$Typer.typed1(Typers.scala:6266)
	scala.tools.nsc.typechecker.Typers$Typer.typed(Typers.scala:6360)
	scala.tools.nsc.typechecker.Typers$Typer.typedStat$1(Typers.scala:6438)
	scala.tools.nsc.typechecker.Typers$Typer.$anonfun$typedStats$5(Typers.scala:3479)
	scala.tools.nsc.typechecker.Typers$Typer.$anonfun$typedStats$5$adapted(Typers.scala:3474)
	scala.reflect.internal.Scopes$Scope.foreach(Scopes.scala:455)
	scala.tools.nsc.typechecker.Typers$Typer.addSynthetics$1(Typers.scala:3474)
	scala.tools.nsc.typechecker.Typers$Typer.typedStats(Typers.scala:3542)
	scala.tools.nsc.typechecker.Typers$Typer.typedTemplate(Typers.scala:2074)
	scala.tools.nsc.typechecker.Typers$Typer.typedClassDef(Typers.scala:1912)
	scala.tools.nsc.typechecker.Typers$Typer.typed1(Typers.scala:6267)
	scala.tools.nsc.typechecker.Typers$Typer.typed(Typers.scala:6360)
	scala.tools.nsc.typechecker.Typers$Typer.typedStat$1(Typers.scala:6438)
	scala.tools.nsc.typechecker.Typers$Typer.$anonfun$typedStats$10(Typers.scala:3530)
	scala.tools.nsc.typechecker.Typers$Typer.typedStats(Typers.scala:3530)
	scala.tools.nsc.typechecker.Typers$Typer.typedPackageDef$1(Typers.scala:5934)
	scala.tools.nsc.typechecker.Typers$Typer.typed1(Typers.scala:6270)
	scala.tools.nsc.typechecker.Typers$Typer.typed(Typers.scala:6360)
	scala.tools.nsc.typechecker.Analyzer$typerFactory$TyperPhase.apply(Analyzer.scala:143)
	scala.tools.nsc.Global$GlobalPhase.applyPhase(Global.scala:485)
	scala.tools.nsc.interactive.Global$TyperRun.applyPhase(Global.scala:1371)
	scala.tools.nsc.interactive.Global$TyperRun.typeCheck(Global.scala:1364)
	scala.tools.nsc.interactive.Global.typeCheck(Global.scala:679)
	scala.meta.internal.pc.WithCompilationUnit.<init>(WithCompilationUnit.scala:24)
	scala.meta.internal.pc.SimpleCollector.<init>(PcCollector.scala:348)
	scala.meta.internal.pc.PcSemanticTokensProvider$Collector$.<init>(PcSemanticTokensProvider.scala:19)
	scala.meta.internal.pc.PcSemanticTokensProvider.Collector$lzycompute$1(PcSemanticTokensProvider.scala:19)
	scala.meta.internal.pc.PcSemanticTokensProvider.Collector(PcSemanticTokensProvider.scala:19)
	scala.meta.internal.pc.PcSemanticTokensProvider.provide(PcSemanticTokensProvider.scala:73)
	scala.meta.internal.pc.ScalaPresentationCompiler.$anonfun$semanticTokens$1(ScalaPresentationCompiler.scala:207)
	scala.meta.internal.pc.CompilerAccess.retryWithCleanCompiler(CompilerAccess.scala:182)
	scala.meta.internal.pc.CompilerAccess.$anonfun$withSharedCompiler$1(CompilerAccess.scala:155)
	scala.Option.map(Option.scala:242)
	scala.meta.internal.pc.CompilerAccess.withSharedCompiler(CompilerAccess.scala:154)
	scala.meta.internal.pc.CompilerAccess.$anonfun$withInterruptableCompiler$1(CompilerAccess.scala:92)
	scala.meta.internal.pc.CompilerAccess.$anonfun$onCompilerJobQueue$1(CompilerAccess.scala:209)
	scala.meta.internal.pc.CompilerJobQueue$Job.run(CompilerJobQueue.scala:152)
	java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1136)
	java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:635)
	java.base/java.lang.Thread.run(Thread.java:840)
```
#### Short summary: 

scala.ScalaReflectionException: value ready_go is not a method