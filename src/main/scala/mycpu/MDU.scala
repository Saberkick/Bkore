
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
  * Vivado Multiplier Generator black box.
  *
  * The synthesis definition is supplied by the Vivado project as an
  * independently generated multiplier XCI.  RTL elaboration intentionally
  * does not copy an XCI from this repository.  The IP is an unsigned 32x32 DSP
  * multiplier with two pipeline stages, II=1, and no CE/reset/flush pins.
  */
class multiplier extends ExtModule {
    override def desiredName: String = "multiplier"

    val io = FlatIO(new Bundle {
        val CLK = Input(Clock())
        val A = Input(UInt(32.W))
        val B = Input(UInt(32.W))
        val P = Output(UInt(64.W))
    })
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
    val resultDone = RegInit(false.B)

    // 输出没有 tready，必须在结果脉冲到达时锁存，才能承受 EX/MEM 的反压。
    // done 和数据都从本地寄存器输出。除法完成因此多一拍，但 WB flush /
    // IP reset 不会再穿过结果选择逻辑回到 RRD/EX payload 的数据输入。
    val qReg = Reg(UInt(32.W))
    val rReg = Reg(UInt(32.W))
    when(ipInReset) {
        resultDone := false.B
    } .otherwise {
        resultDone := resultFire
    }
    when(resultFire) {
        qReg   := resultQ
        rReg   := resultR
        active := false.B
    }

    io.done := resultDone
    io.q    := qReg
    io.r    := rReg
}
