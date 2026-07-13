error id: AF2B14B53C04CFF2F806985D6972B492
file://<WORKSPACE>/src/main/scala/mycpu/CSR.scala
### scala.ScalaReflectionException: value tlbelo0_ppn is not a method

occurred in the presentation compiler.



action parameters:
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

    //TLB Entry High Register
    val tlbehi_vppn  = RegInit(0.U(19.W))

    //TLB Entry Low 0 Register (Even Page)
    val tlbelo0_v    = RegInit(0.U(1.W))
    val tlbelo0_d    = RegInit(0.U(1.W))
    val tlbelo0_plv  = RegInit(0.U(2.W))
    val tlbelo0_mat  = RegInit(0.U(2.W))
    val tlbelo0_g    = RegInit(0.U(1.W))
    val tlbelo0_ppn  = RegInit(0.U(20.W)) /

    //TLB Entry Low 1 Register (Odd Page)
    val tlbelo1_v    = RegInit(0.U(1.W))
    val tlbelo1_d    = RegInit(0.U(1.W))
    val tlbelo1_plv  = RegInit(0.U(2.W))
    val tlbelo1_mat  = RegInit(0.U(2.W))
    val tlbelo1_g    = RegInit(0.U(1.W))
    val tlbelo1_ppn  = RegInit(0.U(20.W))



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


presentation compiler configuration:
Scala version: 2.13.18
Classpath:
<WORKSPACE>/.bloop/root/bloop-bsp-clients-classes/classes-Metals-Kw8gSWc7S6Sh9YOzHQm3aw== [exists ], <HOME>/Library/Caches/bloop/semanticdb/com.sourcegraph.semanticdb-javac.0.11.2/semanticdb-javac-0.11.2.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/scala-library/2.13.18/scala-library-2.13.18.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/chipsalliance/chisel_2.13/7.7.0/chisel_2.13-7.7.0.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/github/scopt/scopt_2.13/4.1.0/scopt_2.13-4.1.0.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/apache/commons/commons-text/1.15.0/commons-text-1.15.0.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/os-lib_2.13/0.10.7/os-lib_2.13-0.10.7.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/github/json4s/json4s-native_2.13/4.1.0/json4s-native_2.13-4.1.0.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/github/alexarchambault/data-class_2.13/0.2.7/data-class_2.13-0.2.7.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/scala-reflect/2.13.18/scala-reflect-2.13.18.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/upickle_2.13/3.3.1/upickle_2.13-3.3.1.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/chipsalliance/firtool-resolver_2.13/2.0.1/firtool-resolver_2.13-2.0.1.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/apache/commons/commons-lang3/3.20.0/commons-lang3-3.20.0.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/geny_2.13/1.1.1/geny_2.13-1.1.1.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/github/json4s/json4s-core_2.13/4.1.0/json4s-core_2.13-4.1.0.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/github/json4s/json4s-native-core_2.13/4.1.0/json4s-native-core_2.13-4.1.0.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/ujson_2.13/3.3.1/ujson_2.13-3.3.1.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/upack_2.13/3.3.1/upack_2.13-3.3.1.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/upickle-implicits_2.13/3.3.1/upickle-implicits_2.13-3.3.1.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/modules/scala-xml_2.13/2.2.0/scala-xml_2.13-2.2.0.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/modules/scala-collection-compat_2.13/2.11.0/scala-collection-compat_2.13-2.11.0.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/github/json4s/json4s-ast_2.13/4.1.0/json4s-ast_2.13-4.1.0.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/github/json4s/json4s-scalap_2.13/4.1.0/json4s-scalap_2.13-4.1.0.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/upickle-core_2.13/3.3.1/upickle-core_2.13-3.3.1.jar [exists ]
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

scala.ScalaReflectionException: value tlbelo0_ppn is not a method