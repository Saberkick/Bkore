package mycpu

import circt.stage.ChiselStage
import java.nio.file.{Files, Paths, StandardCopyOption}

object Elaborate extends App {
    ChiselStage.emitSystemVerilogFile(
        new mycpu_top(),
        firtoolOpts = Array(
            "-disable-all-randomization",
            "-strip-debug-info"
        )
    )

    val adapterSource = Paths.get("src", "main", "resources", "chiplab", "core_top.sv")
        .toAbsolutePath
        .normalize()
    require(Files.isRegularFile(adapterSource), s"Missing Chiplab adapter: $adapterSource")
    Files.copy(
        adapterSource,
        targetDir.resolve("core_top.sv"),
        StandardCopyOption.REPLACE_EXISTING
    )

    val zeroInitSource = Paths.get("zero_init.hex").toAbsolutePath.normalize()
    require(Files.isRegularFile(zeroInitSource), s"Missing cache initialization file: $zeroInitSource")
    Files.copy(
        zeroInitSource,
        targetDir.resolve("zero_init.hex"),
        StandardCopyOption.REPLACE_EXISTING
    )
}
