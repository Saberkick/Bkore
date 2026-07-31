// Simulation-only model for the div_gen_0 XCI used by Divider.scala.
// Do not add this file to Vivado synthesis sources.
module div_gen_0 (
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
    reg [5:0] count;
    reg [63:0] result;
    wire accept = s_axis_divisor_tvalid && s_axis_dividend_tvalid &&
                  s_axis_divisor_tready && s_axis_dividend_tready;

    assign s_axis_divisor_tready = (count == 0);
    assign s_axis_dividend_tready = (count == 0);

    always @(posedge aclk) begin
        if (!aresetn) begin
            count <= 0;
            result <= 0;
            m_axis_dout_tvalid <= 0;
            m_axis_dout_tdata <= 0;
        end else begin
            m_axis_dout_tvalid <= 0;
            if (accept) begin
                result <= {
                    s_axis_dividend_tdata / s_axis_divisor_tdata,
                    s_axis_dividend_tdata % s_axis_divisor_tdata
                };
                count <= 34;
            end else if (count != 0) begin
                count <= count - 1;
                if (count == 1) begin
                    m_axis_dout_tvalid <= 1;
                    m_axis_dout_tdata <= result;
                end
            end
        end
    end
endmodule
