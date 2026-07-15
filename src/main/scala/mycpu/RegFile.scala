package mycpu

import chisel3._

class Regfile extends Module{
	val io = IO(new Bundle{
		val raddr1 	= Input(UInt(5.W))
		val rdata1	= Output(UInt(32.W))
		val raddr2 	= Input(UInt(5.W))
		val rdata2 	= Output(UInt(32.W))

		val we 		= Input(Bool())
		val waddr 	= Input(UInt(5.W))
		val wdata	= Input(UInt(32.W))
	})

	// 2R1W 寄存器堆；同拍 WB 写与 ID 读同一地址时直接旁路写数据（write-first）。
	// x0/r0 恒为零且禁止写入，这是 WB -> ID 的最后一级零周期旁路。
	val regs = RegInit(VecInit(Seq.fill(32)(0.U(32.W))))
	when (io.we && io.waddr =/= 0.U) {
		regs(io.waddr) := io.wdata
	}
	io.rdata1 := Mux(io.raddr1 === 0.U, 0.U, 
                 Mux(io.we && (io.waddr === io.raddr1), io.wdata, 
                 regs(io.raddr1)))
                 
    io.rdata2 := Mux(io.raddr2 === 0.U, 0.U, 
                 Mux(io.we && (io.waddr === io.raddr2), io.wdata, 
                 regs(io.raddr2)))
}
