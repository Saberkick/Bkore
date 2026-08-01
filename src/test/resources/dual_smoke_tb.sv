`timescale 1ns/1ps

module dual_smoke_tb;
    reg clock = 1'b0;
    reg reset = 1'b1;
    wire done;

    DualSmokeHarness dut (
        .clock(clock),
        .reset(reset),
        .io_done(done)
    );

    always #5 clock = ~clock;

    initial begin
        repeat (3) @(posedge clock);
        reset <= 1'b0;
        repeat (24) @(posedge clock);
        if (!done) begin
            $fatal(1, "DualSmokeHarness did not finish");
        end
        $display("DUAL_SMOKE_PASS");
        $finish;
    end
endmodule
