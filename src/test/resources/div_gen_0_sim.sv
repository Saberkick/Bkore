// Simulation-only model for the Vivado Divider Generator wrapper.
//
// This file intentionally lives under src/test/resources and must never be
// copied into IP/myCPU or added to a Vivado synthesis file set.  The hardware
// build continues to resolve div_gen_0 from its XCI output products.
module div_gen_0 #(
    parameter integer LATENCY = 8
) (
    input  wire        aclk,
    input  wire        aresetn,
    input  wire        s_axis_divisor_tvalid,
    output wire        s_axis_divisor_tready,
    input  wire [31:0] s_axis_divisor_tdata,
    input  wire        s_axis_dividend_tvalid,
    output wire        s_axis_dividend_tready,
    input  wire [31:0] s_axis_dividend_tdata,
    output reg         m_axis_dout_tvalid,
    output reg  [63:0] m_axis_dout_tdata
);
    reg busy;
    integer remaining;
    reg [31:0] quotient;
    reg [31:0] remainder;

    assign s_axis_divisor_tready  = !busy;
    assign s_axis_dividend_tready = !busy;

    always @(posedge aclk) begin
        if (!aresetn) begin
            busy               <= 1'b0;
            remaining          <= 0;
            quotient           <= 32'b0;
            remainder          <= 32'b0;
            m_axis_dout_tvalid  <= 1'b0;
            m_axis_dout_tdata   <= 64'b0;
        end else begin
            m_axis_dout_tvalid <= 1'b0;

            if (!busy &&
                s_axis_divisor_tvalid && s_axis_dividend_tvalid) begin
                busy      <= 1'b1;
                remaining <= LATENCY;
                quotient  <= s_axis_dividend_tdata / s_axis_divisor_tdata;
                remainder <= s_axis_dividend_tdata % s_axis_divisor_tdata;
            end else if (busy && remaining > 1) begin
                remaining <= remaining - 1;
            end else if (busy) begin
                busy              <= 1'b0;
                remaining         <= 0;
                m_axis_dout_tvalid <= 1'b1;
                m_axis_dout_tdata  <= {quotient, remainder};
            end
        end
    end
endmodule
