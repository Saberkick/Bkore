package mycpu

import circt.stage.ChiselStage
import java.nio.file.{Files, Paths, StandardCopyOption}

object Elaborate extends App {
    ChiselStage.emitSystemVerilogFile(
        new core_top(),
        firtoolOpts = Array(
            "-disable-all-randomization",
            "-strip-debug-info"
        ),
        args = Array(
            "--target-dir",
            "D:\\Develop\\CPU\\Archive\\mycpu\\generated"
        )
    )
}
