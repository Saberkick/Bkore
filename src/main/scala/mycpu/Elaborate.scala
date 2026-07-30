package mycpu

import circt.stage.ChiselStage

/** Default and competition-facing entry point: always emit the dual-issue core. */
object Elaborate extends App {
    val targetDir = args.sliding(2).collectFirst {
        case Array("--target-dir", value) => value
    }.getOrElse("generated/vivado-dual")

    ChiselStage.emitSystemVerilogFile(
        new DualCoreTop(),
        firtoolOpts = Array(
            "-disable-all-randomization",
            "-strip-debug-info"
        ),
        args = Array(
            "--target-dir",
            targetDir
        )
    )
}
