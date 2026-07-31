if {$argc != 1} {
    puts "Usage: vivado -mode batch -source create_divider_ip.tcl -tclargs <ip-parent-directory>"
    exit 2
}

set ip_parent [file normalize [lindex $argv 0]]
file mkdir $ip_parent

create_project -in_memory -part xc7a200tfbg676-2
set_property target_language Verilog [current_project]
set_property simulator_language Mixed [current_project]

set definitions [get_ipdefs -all xilinx.com:ip:div_gen:5.1]
if {[llength $definitions] != 1} {
    puts "ERROR: expected one Vivado Divider Generator 5.1 definition, got [llength $definitions]"
    exit 3
}

create_ip \
    -vlnv xilinx.com:ip:div_gen:5.1 \
    -module_name div_gen_0 \
    -dir $ip_parent

# Unsigned 32-bit quotient/remainder divider.  The Radix-2 blocking AXI
# stream configuration has automatic latency 35 and accepts one operation
# at a time, matching Divider.scala.
set_property -dict [list \
    CONFIG.Component_Name {div_gen_0} \
    CONFIG.algorithm_type {Radix2} \
    CONFIG.dividend_and_quotient_width {32} \
    CONFIG.divisor_width {32} \
    CONFIG.remainder_type {Remainder} \
    CONFIG.operand_sign {Unsigned} \
    CONFIG.clocks_per_division {1} \
    CONFIG.FlowControl {Blocking} \
    CONFIG.OptimizeGoal {Performance} \
    CONFIG.latency_configuration {Automatic} \
    CONFIG.ARESETN {true} \
    CONFIG.ACLKEN {false} \
    CONFIG.OutTready {false} \
    CONFIG.divide_by_zero_detect {false}] \
    [get_ips div_gen_0]

generate_target all [get_ips div_gen_0]
export_ip_user_files \
    -of_objects [get_ips div_gen_0] \
    -no_script \
    -sync \
    -force \
    -quiet

set xci [get_files -quiet -all */div_gen_0.xci]
if {[llength $xci] != 1} {
    puts "ERROR: div_gen_0.xci was not generated uniquely"
    exit 4
}

puts "DIVIDER_XCI=$xci"
puts "DIVIDER_IPDEF=[get_property IPDEF [get_ips div_gen_0]]"
puts "DIVIDER_LOCKED=[get_property IS_LOCKED [get_ips div_gen_0]]"
puts "DIVIDER_SIM_MODEL=[get_property SELECTED_SIM_MODEL [get_ips div_gen_0]]"
close_project
exit 0
