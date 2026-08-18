// Simulation-only model for the Vivado Multiplier Generator wrapper.
//
// This file intentionally lives under src/test/resources and must never be
// copied into IP/myCPU or added to a Vivado synthesis file set.  The hardware
// build continues to resolve mult_gen_0 from its XCI output products.
//
// Port/behaviour matches a 33x33 signed parallel multiplier configured with
// Pipeline Stages = 1: the product is registered one cycle after the operands.
module mult_gen_0(
    input  wire        CLK,
    input  wire [32:0] A,
    input  wire [32:0] B,
    output wire [65:0] P
);
    reg [65:0] p_reg;

    always @(posedge CLK) begin
        p_reg <= $signed(A) * $signed(B);
    end

    assign P = p_reg;
endmodule
