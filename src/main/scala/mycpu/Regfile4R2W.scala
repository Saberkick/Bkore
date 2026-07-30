package mycpu

import chisel3._

/** Four asynchronous read ports and two ordered write ports. */
class Regfile4R2W extends Module {
    val io = IO(new Bundle {
        val raddr = Input(Vec(4, UInt(5.W)))
        val rdata = Output(Vec(4, UInt(32.W)))

        val wen   = Input(Vec(2, Bool()))
        val waddr = Input(Vec(2, UInt(5.W)))
        val wdata = Input(Vec(2, UInt(32.W)))

        val inspectAddr = Input(UInt(5.W))
        val inspectData = Output(UInt(32.W))
    })

    val regs = RegInit(VecInit(Seq.fill(32)(0.U(32.W))))

    when(io.wen(0) && io.waddr(0) =/= 0.U) {
        regs(io.waddr(0)) := io.wdata(0)
    }
    when(io.wen(1) && io.waddr(1) =/= 0.U) {
        // Port 1 is younger and therefore has priority if a defensive caller
        // ever presents a WAW pair despite the issue rule.
        regs(io.waddr(1)) := io.wdata(1)
    }

    private def readWithBypass(addr: UInt): UInt = {
        Mux(addr === 0.U, 0.U,
            Mux(io.wen(1) && io.waddr(1) === addr && io.waddr(1) =/= 0.U, io.wdata(1),
            Mux(io.wen(0) && io.waddr(0) === addr && io.waddr(0) =/= 0.U, io.wdata(0),
                regs(addr))))
    }

    for (port <- 0 until 4) {
        io.rdata(port) := readWithBypass(io.raddr(port))
    }
    io.inspectData := readWithBypass(io.inspectAddr)
}
