
package mycpu

import chisel3._
import chisel3.util._


// Vivado Divider Generator v5.1 的纯黑盒声明。
//
// 这里不能再用 setInline 提供带“/、%”的行为级 Verilog，否则 Vivado 会把它
// 当作普通 RTL 综合，重新产生一条很长的组合除法路径。工程中必须另外加入
// 名为 div_gen_0 的 XCI/IP output products；具体配置见 DIVIDER_IP_VIVADO.md。
class div_gen_0 extends ExtModule {
    val io = FlatIO(new Bundle {
        val aclk                    = Input(Clock())
        val aresetn                 = Input(Bool())
        val s_axis_divisor_tvalid   = Input(Bool())
        val s_axis_divisor_tready   = Output(Bool())
        val s_axis_divisor_tdata    = Input(UInt(32.W))
        val s_axis_dividend_tvalid  = Input(Bool())
        val s_axis_dividend_tready  = Output(Bool())
        val s_axis_dividend_tdata   = Input(UInt(32.W))
        val m_axis_dout_tvalid      = Output(Bool())
        val m_axis_dout_tdata       = Output(UInt(64.W))
    })
}
/**
  * Vivado Multiplier Generator v12.0 的纯黑盒声明。
  *
  * 一个 33x33 有符号并行乘法，配置 1 级流水（积寄存在 DSP 内部的 MREG/PREG），
  * 因此 66 位积不再从 DSP 组合输出拉到 fabric FF。工程里必须另外加入名为
  * mult_gen_0 的 XCI/output products；Verilator 用 src/test/resources 下的
  * mult_gen_0_sim.sv 行为模型。具体配置见 MULTIPLIER_IP_VIVADO.md。
  */
class mult_gen_0 extends ExtModule {
    val io = FlatIO(new Bundle {
        val CLK = Input(Clock())
        val A   = Input(UInt(33.W))
        val B   = Input(UInt(33.W))
        val P   = Output(UInt(66.W))
    })
}

/**
  * One-request pipelined integer multiplier.
  *
  * The 33x33 signed product is captured by the IP's internal pipeline register
  * (one cycle).  High/low word selection is placed after that register, so the
  * FPGA DSP cascade cannot extend through the EX result mux into the next
  * stage.  `done` remains asserted until the backend consumes the result,
  * which also makes the unit safe when the whole EX packet is held by
  * downstream backpressure.
  */
class Multiplier extends Module {
    val io = IO(new Bundle {
        val enable   = Input(Bool())
        val flush    = Input(Bool())
        val consume  = Input(Bool())
        val src1     = Input(UInt(32.W))
        val src2     = Input(UInt(32.W))
        val isSigned = Input(Bool())
        val highWord = Input(Bool())
        val result   = Output(UInt(32.W))
        val done     = Output(Bool())
    })

    val mul = Module(new mult_gen_0())
    mul.io.CLK := clock
    // A sign-extension covers both signed and unsigned products: the low 64
    // bits are identical to the corresponding 32x32 result.  The IP is always
    // configured as a 33x33 signed multiplier.
    mul.io.A := Cat(io.isSigned && io.src1(31), io.src1)
    mul.io.B := Cat(io.isSigned && io.src2(31), io.src2)

    // The product is registered inside the DSP, so only the one-bit validity
    // and the high/low select remain in fabric.
    val productValid = RegInit(false.B)
    val highWordReg  = RegInit(false.B)

    when(io.flush) {
        productValid := false.B
    }.elsewhen(io.enable && !productValid) {
        highWordReg := io.highWord
        productValid := true.B
    }.elsewhen(io.consume) {
        productValid := false.B
    }

    io.result := Mux(highWordReg, mul.io.P(63, 32), mul.io.P(31, 0))
    io.done := productValid
}

class Divider extends Module {
    val io = IO(new Bundle {
        val enable = Input(Bool())
        val flush  = Input(Bool())
        val a      = Input(UInt(32.W))
        val b      = Input(UInt(32.W))
        val ready  = Output(Bool())
        val q      = Output(UInt(32.W))
        val r      = Output(UInt(32.W))
        val done   = Output(Bool())
    })

    val div_ip = Module(new div_gen_0())

    // Divider Generator 的 ARESETn 是同步、低有效复位，PG151 要求至少保持两拍。
    // flush 可能只持续一拍，因此额外保持三拍；保持期间不接收新的 CPU 请求。
    val resetHold = RegInit(3.U(2.W))
    when(io.flush) {
        resetHold := 3.U
    } .elsewhen(resetHold =/= 0.U) {
        resetHold := resetHold - 1.U
    }
    val ipInReset = reset.asBool || io.flush || resetHold.orR

    // Wrapper 只允许一个除法在途。操作数先在本地锁存，再分别遵守两个 AXIS
    // 输入通道的 ready/valid 握手，因此 IP 采用可变延迟或降低吞吐率也不会丢请求。
    val active          = RegInit(false.B)
    val dividendPending = RegInit(false.B)
    val divisorPending  = RegInit(false.B)
    val dividendReg     = Reg(UInt(32.W))
    val divisorReg      = Reg(UInt(32.W))

    io.ready := !active && !ipInReset
    val accept = io.enable && io.ready
    val acceptDivideByZero = accept && (io.b === 0.U)

    when(ipInReset) {
        active          := false.B
        dividendPending := false.B
        divisorPending  := false.B
    } .otherwise {
        when(accept) {
            active      := true.B
            dividendReg := io.a
            divisorReg  := io.b

            // 除零结果由 wrapper 定义，不把未定义操作送进厂商 IP。
            dividendPending := io.b =/= 0.U
            divisorPending  := io.b =/= 0.U
        }

        when(dividendPending && div_ip.io.s_axis_dividend_tready) {
            dividendPending := false.B
        }
        when(divisorPending && div_ip.io.s_axis_divisor_tready) {
            divisorPending := false.B
        }
    }

    div_ip.io.aclk                    := clock
    div_ip.io.aresetn                 := !ipInReset
    div_ip.io.s_axis_dividend_tvalid  := active && dividendPending && !ipInReset
    div_ip.io.s_axis_dividend_tdata   := dividendReg
    div_ip.io.s_axis_divisor_tvalid   := active && divisorPending && !ipInReset
    div_ip.io.s_axis_divisor_tdata    := divisorReg

    // 除零沿用原设计的结果约定：商全 1，余数为被除数，并固定延迟一拍返回。
    val divideByZeroPending = RegInit(false.B)
    when(ipInReset) {
        divideByZeroPending := false.B
    } .otherwise {
        divideByZeroPending := acceptDivideByZero
    }

    val resultValid = divideByZeroPending || div_ip.io.m_axis_dout_tvalid
    val resultQ = Mux(divideByZeroPending, "hffffffff".U, div_ip.io.m_axis_dout_tdata(63, 32))
    val resultR = Mux(divideByZeroPending, dividendReg,     div_ip.io.m_axis_dout_tdata(31, 0))
    val resultFire = active && resultValid && !ipInReset

    // 输出没有 tready，必须在结果脉冲到达时锁存，才能承受 EX/MEM 的反压。
    val qReg = Reg(UInt(32.W))
    val rReg = Reg(UInt(32.W))
    when(resultFire) {
        qReg   := resultQ
        rReg   := resultR
        active := false.B
    }

    io.done := resultFire
    io.q    := Mux(resultFire, resultQ, qReg)
    io.r    := Mux(resultFire, resultR, rReg)
}
