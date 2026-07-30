#include "VDualSmokeHarness.h"
#include "verilated.h"

static void tick(VDualSmokeHarness& dut) {
    dut.clock = 0;
    dut.eval();
    dut.clock = 1;
    dut.eval();
}

int main(int argc, char** argv) {
    Verilated::commandArgs(argc, argv);
    VDualSmokeHarness dut;

    dut.reset = 1;
    for (int i = 0; i < 3; ++i) tick(dut);
    dut.reset = 0;

    for (int cycle = 0; cycle < 20 && !Verilated::gotFinish(); ++cycle) {
        tick(dut);
        if (dut.io_done) {
            VL_PRINTF("DUAL_SMOKE_PASS\n");
            return 0;
        }
    }
    VL_PRINTF("DUAL_SMOKE_TIMEOUT\n");
    return 1;
}
