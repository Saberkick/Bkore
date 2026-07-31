// Simulation-only model for the multiplier.xci black box.
// Do not add this file to Vivado synthesis sources together with the XCI.
module multiplier (
    input  wire        CLK,
    input  wire [31:0] A,
    input  wire [31:0] B,
    output wire [63:0] P
);
    reg [63:0] product_stage0;
    reg [63:0] product_stage1;

    always @(posedge CLK) begin
        product_stage0 <= A * B;
        product_stage1 <= product_stage0;
    end

    assign P = product_stage1;
endmodule
