package mycpu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

private object NativeSimulatorGuard {
    def requireAvailable(): Unit = {
        org.scalatest.Assertions.assume(
            sys.env.get("CHISEL_NATIVE_SIM").contains("1"),
            "Set CHISEL_NATIVE_SIM=1 after installing a native Windows Verilator toolchain"
        )
    }
}

class DualIssueUnitSpec extends AnyFlatSpec with ChiselSim with Matchers {
    behavior of "DualIssueUnit"

    private def clearLane(dut: DualIssueUnit, lane: Int): Unit = {
        dut.io.in(lane).valid.poke(false.B)
        dut.io.in(lane).src1Read.poke(false.B)
        dut.io.in(lane).src1.poke(0.U)
        dut.io.in(lane).src2Read.poke(false.B)
        dut.io.in(lane).src2.poke(0.U)
        dut.io.in(lane).regWrite.poke(false.B)
        dut.io.in(lane).dest.poke(0.U)
        dut.io.in(lane).isMem.poke(false.B)
        dut.io.in(lane).memBank.poke(false.B)
        dut.io.in(lane).cacheable.poke(true.B)
        dut.io.in(lane).isBranch.poke(false.B)
        dut.io.in(lane).isMdu.poke(false.B)
        dut.io.in(lane).isMul.poke(false.B)
        dut.io.in(lane).isDiv.poke(false.B)
        dut.io.in(lane).predictedTaken.poke(false.B)
        dut.io.in(lane).isSerializing.poke(false.B)
        dut.io.in(lane).hasException.poke(false.B)
    }

    it should "issue independent integer instructions together" in {
        NativeSimulatorGuard.requireAvailable()
        simulate(new DualIssueUnit()) { dut =>
            clearLane(dut, 0)
            clearLane(dut, 1)
            dut.io.in(0).valid.poke(true.B)
            dut.io.in(0).regWrite.poke(true.B)
            dut.io.in(0).dest.poke(3.U)
            dut.io.in(1).valid.poke(true.B)
            dut.io.in(1).src1Read.poke(true.B)
            dut.io.in(1).src1.poke(4.U)
            dut.io.in(1).regWrite.poke(true.B)
            dut.io.in(1).dest.poke(5.U)
            dut.io.issueCount.expect(2.U)
            dut.io.issueValid(1).expect(true.B)
        }
    }

    it should "preserve age across RAW, WAW and structural conflicts" in {
        NativeSimulatorGuard.requireAvailable()
        simulate(new DualIssueUnit()) { dut =>
            clearLane(dut, 0)
            clearLane(dut, 1)
            dut.io.in(0).valid.poke(true.B)
            dut.io.in(1).valid.poke(true.B)
            dut.io.in(0).regWrite.poke(true.B)
            dut.io.in(0).dest.poke(7.U)
            dut.io.in(1).src1Read.poke(true.B)
            dut.io.in(1).src1.poke(7.U)
            dut.io.issueCount.expect(1.U)
            dut.io.blockMask.expect(DualIssueBlockReason.Raw)

            dut.io.in(1).src1Read.poke(false.B)
            dut.io.in(1).regWrite.poke(true.B)
            dut.io.in(1).dest.poke(7.U)
            dut.io.issueCount.expect(1.U)
            dut.io.blockMask.expect(DualIssueBlockReason.Waw)

            dut.io.in(1).regWrite.poke(false.B)
            dut.io.in(0).isMem.poke(true.B)
            dut.io.in(1).isMem.poke(true.B)
            dut.io.issueCount.expect(1.U)
            dut.io.blockMask.expect(DualIssueBlockReason.TwoMem)
        }
    }

    it should "allow a safe lane0 or lane1 branch beside a simple instruction" in {
        NativeSimulatorGuard.requireAvailable()
        simulate(new DualIssueUnit()) { dut =>
            clearLane(dut, 0)
            clearLane(dut, 1)
            dut.io.in(0).valid.poke(true.B)
            dut.io.in(1).valid.poke(true.B)
            dut.io.in(1).isBranch.poke(true.B)
            dut.io.issueCount.expect(2.U)

            dut.io.in(0).isBranch.poke(true.B)
            dut.io.in(1).isBranch.poke(false.B)
            dut.io.issueCount.expect(2.U)

            dut.io.in(0).predictedTaken.poke(true.B)
            dut.io.issueCount.expect(1.U)
            dut.io.blockMask.expect(DualIssueBlockReason.Slot0Ctrl)
        }
    }

    it should "pair different-bank cached accesses and a multiply with an ALU" in {
        NativeSimulatorGuard.requireAvailable()
        simulate(new DualIssueUnit()) { dut =>
            clearLane(dut, 0)
            clearLane(dut, 1)
            dut.io.in(0).valid.poke(true.B)
            dut.io.in(1).valid.poke(true.B)
            dut.io.in(0).isMem.poke(true.B)
            dut.io.in(1).isMem.poke(true.B)
            dut.io.in(0).memBank.poke(false.B)
            dut.io.in(1).memBank.poke(true.B)
            dut.io.issueCount.expect(2.U)

            dut.io.in(1).memBank.poke(false.B)
            dut.io.issueCount.expect(1.U)
            dut.io.blockMask.expect(DualIssueBlockReason.TwoMem)

            dut.io.in(0).isMem.poke(false.B)
            dut.io.in(1).isMem.poke(false.B)
            dut.io.in(0).isMdu.poke(true.B)
            dut.io.in(0).isMul.poke(true.B)
            dut.io.issueCount.expect(2.U)

            dut.io.in(0).isMul.poke(false.B)
            dut.io.in(0).isDiv.poke(true.B)
            dut.io.issueCount.expect(1.U)
        }
    }
}

class DualBranchPredictorSpec extends AnyFlatSpec with ChiselSim {
    behavior of "DualBranchPredictor"

    it should "return a trained target one cycle after the banked request" in {
        NativeSimulatorGuard.requireAvailable()
        simulate(new DualBranchPredictor(clearSets = 4)) { dut =>
            dut.io.reqValid.poke(false.B)
            dut.io.reqPc(0).poke("h1c000000".U)
            dut.io.reqPc(1).poke("h1c000004".U)
            dut.io.consume.poke(false.B)
            dut.io.consumeSlot.poke(0.U)
            dut.io.flush.poke(false.B)
            dut.io.rasCommit.asUInt.poke(0.U)
            dut.io.update.asUInt.poke(0.U)
            dut.clock.step(5)

            dut.io.update.valid.poke(true.B)
            dut.io.update.pc.poke("h1c000000".U)
            dut.io.update.isBranch.poke(true.B)
            dut.io.update.target.poke("h1c001000".U)
            dut.clock.step()
            dut.io.update.valid.poke(false.B)
            dut.clock.step()

            dut.io.reqValid.poke(true.B)
            dut.clock.step()
            dut.io.reqValid.poke(false.B)
            dut.io.resultValid.expect(true.B)
            dut.io.result(0).hit.expect(true.B)
            dut.io.result(0).taken.expect(true.B)
            dut.io.result(0).target.expect("h1c001000".U)

            // F2 can remain stalled after resultValid falls.  The predictor
            // payload must stay deterministic until the frontend consumes it.
            dut.clock.step(3)
            dut.io.resultValid.expect(false.B)
            dut.io.result(0).hit.expect(true.B)
            dut.io.result(0).taken.expect(true.B)
            dut.io.result(0).target.expect("h1c001000".U)
        }
    }
}

class DualInstructionQueueSpec extends AnyFlatSpec with ChiselSim {
    behavior of "DualInstructionQueue"

    it should "enqueue, pop and flush in architectural order" in {
        NativeSimulatorGuard.requireAvailable()
        simulate(new DualInstructionQueue()) { dut =>
            dut.io.flush.poke(false.B)
            dut.io.popCount.poke(0.U)
            dut.io.enqValid(0).poke(true.B)
            dut.io.enqValid(1).poke(true.B)
            dut.io.enqBits(0).asUInt.poke(0.U)
            dut.io.enqBits(1).asUInt.poke(0.U)
            dut.io.enqBits(0).pc.poke("h1c000000".U)
            dut.io.enqBits(1).pc.poke("h1c000004".U)
            dut.clock.step()

            dut.io.enqValid(0).poke(false.B)
            dut.io.enqValid(1).poke(false.B)
            dut.io.count.expect(2.U)
            dut.io.deqBits(0).pc.expect("h1c000000".U)
            dut.io.deqBits(1).pc.expect("h1c000004".U)

            dut.io.popCount.poke(1.U)
            dut.clock.step()
            dut.io.popCount.poke(0.U)
            dut.io.count.expect(1.U)
            dut.io.deqBits(0).pc.expect("h1c000004".U)

            dut.io.flush.poke(true.B)
            dut.clock.step()
            dut.io.flush.poke(false.B)
            dut.io.count.expect(0.U)
            dut.io.deqValid(0).expect(false.B)
        }
    }
}

class Regfile4R2WSpec extends AnyFlatSpec with ChiselSim {
    behavior of "Regfile4R2W"

    it should "provide two ordered writes and write-first reads" in {
        NativeSimulatorGuard.requireAvailable()
        simulate(new Regfile4R2W()) { dut =>
            for (port <- 0 until 4) dut.io.raddr(port).poke(0.U)
            dut.io.inspectAddr.poke(0.U)
            dut.io.wen(0).poke(true.B)
            dut.io.waddr(0).poke(3.U)
            dut.io.wdata(0).poke("h11111111".U)
            dut.io.wen(1).poke(true.B)
            dut.io.waddr(1).poke(4.U)
            dut.io.wdata(1).poke("h22222222".U)
            dut.io.raddr(0).poke(3.U)
            dut.io.raddr(1).poke(4.U)
            dut.io.rdata(0).expect("h11111111".U)
            dut.io.rdata(1).expect("h22222222".U)
            dut.clock.step()

            dut.io.wen(0).poke(false.B)
            dut.io.wen(1).poke(false.B)
            dut.io.rdata(0).expect("h11111111".U)
            dut.io.rdata(1).expect("h22222222".U)
        }
    }
}

class MultiplierSpec extends AnyFlatSpec with ChiselSim {
    behavior of "Multiplier"

    it should "register the 33x33 product before selecting the result word" in {
        NativeSimulatorGuard.requireAvailable()
        simulate(new Multiplier()) { dut =>
            dut.io.enable.poke(false.B)
            dut.io.flush.poke(false.B)
            dut.io.consume.poke(false.B)
            dut.io.src1.poke("hffffffff".U)
            dut.io.src2.poke(2.U)
            dut.io.isSigned.poke(false.B)
            dut.io.highWord.poke(true.B)
            dut.io.done.expect(false.B)

            dut.io.enable.poke(true.B)
            dut.clock.step()
            dut.io.enable.poke(false.B)
            dut.io.done.expect(true.B)
            dut.io.result.expect(1.U)

            dut.clock.step(2)
            dut.io.done.expect(true.B)
            dut.io.result.expect(1.U)

            dut.io.consume.poke(true.B)
            dut.clock.step()
            dut.io.consume.poke(false.B)
            dut.io.done.expect(false.B)
        }
    }
}

class AtomicDecodeSpec extends AnyFlatSpec with ChiselSim {
    behavior of "LoongArch atomic decode"

    it should "decode scaled ll.w and sc.w immediates" in {
        NativeSimulatorGuard.requireAvailable()
        simulate(new Decoder()) { dut =>
            val ll = (0x08L << 26) | (0x3fffL << 10) | (2L << 5) | 3L
            dut.io.inst.poke(ll.U)
            dut.io.out.isLL.expect(true.B)
            dut.io.out.resFromMem.expect(true.B)
            dut.io.out.imm.expect("hfffffffc".U)

            val sc = (0x09L << 26) | (1L << 10) | (2L << 5) | 3L
            dut.io.inst.poke(sc.U)
            dut.io.out.isSC.expect(true.B)
            dut.io.out.memWe.expect(true.B)
            dut.io.out.regWe.expect(true.B)
            dut.io.out.imm.expect(4.U)
        }
    }
}

class CsrLlbitSpec extends AnyFlatSpec with ChiselSim {
    behavior of "CSR LLBCTL"

    private def defaults(dut: CSR): Unit = {
        dut.io.addr.poke(CsrAddr.LLBCTL)
        dut.io.writeEn.poke(false.B)
        dut.io.writeData.poke(0.U)
        dut.io.writeMask.poke(0.U)
        dut.io.excValid.poke(false.B)
        dut.io.excEcode.poke(0.U)
        dut.io.excEsubcode.poke(0.U)
        dut.io.excPc.poke(0.U)
        dut.io.excAddr.poke(0.U)
        dut.io.ertnFlush.poke(false.B)
        dut.io.hw_int_in.poke(0.U)
        dut.io.tlbrd_we.poke(false.B)
        dut.io.tlbrd_in.asUInt.poke(0.U)
        dut.io.llbitSet.poke(false.B)
        dut.io.llbitClear.poke(false.B)
    }

    it should "implement WCLLB and one-shot KLO semantics" in {
        NativeSimulatorGuard.requireAvailable()
        simulate(new CSR()) { dut =>
            defaults(dut)
            dut.io.llbitSet.poke(true.B)
            dut.clock.step()
            dut.io.llbitSet.poke(false.B)
            dut.io.llbit.expect(true.B)

            dut.io.writeEn.poke(true.B)
            dut.io.writeData.poke(4.U)
            dut.io.writeMask.poke(4.U)
            dut.clock.step()
            dut.io.writeEn.poke(false.B)
            dut.io.ertnFlush.poke(true.B)
            dut.clock.step()
            dut.io.ertnFlush.poke(false.B)
            dut.io.llbit.expect(true.B)
            dut.io.readData.expect(1.U)

            dut.io.ertnFlush.poke(true.B)
            dut.clock.step()
            dut.io.ertnFlush.poke(false.B)
            dut.io.llbit.expect(false.B)
        }
    }
}
