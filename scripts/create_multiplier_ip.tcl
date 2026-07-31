if {$argc != 1} {
    puts "Usage: vivado -mode batch -source create_multiplier_ip.tcl -tclargs <ip-parent-directory>"
    exit 2
}

set ip_parent [file normalize [lindex $argv 0]]
file mkdir $ip_parent

create_project -in_memory -part xc7a200tfbg676-2
set_property target_language Verilog [current_project]
set_property simulator_language Mixed [current_project]

set definitions [get_ipdefs -all xilinx.com:ip:mult_gen:12.0]
if {[llength $definitions] != 1} {
    puts "ERROR: expected one Vivado Multiplier Generator 12.0 definition, got [llength $definitions]"
    exit 3
}

create_ip \
    -vlnv xilinx.com:ip:mult_gen:12.0 \
    -module_name multiplier \
    -dir $ip_parent

# Unsigned 32x32 -> 64, two registered stages, II=1.  This matches the
# ExtModule contract in MDU.scala and uses DSP blocks for the implementation.
set_property -dict [list \
    CONFIG.Component_Name {multiplier} \
    CONFIG.MultType {Parallel_Multiplier} \
    CONFIG.PortAType {Unsigned} \
    CONFIG.PortAWidth {32} \
    CONFIG.PortBType {Unsigned} \
    CONFIG.PortBWidth {32} \
    CONFIG.Multiplier_Construction {Use_Mults} \
    CONFIG.OptGoal {Speed} \
    CONFIG.PipeStages {2} \
    CONFIG.Use_Custom_Output_Width {false} \
    CONFIG.OutputWidthHigh {63} \
    CONFIG.OutputWidthLow {0} \
    CONFIG.ClockEnable {false} \
    CONFIG.SyncClear {false}] \
    [get_ips multiplier]

generate_target all [get_ips multiplier]
export_ip_user_files \
    -of_objects [get_ips multiplier] \
    -no_script \
    -sync \
    -force \
    -quiet

set xci [get_files -quiet -all */multiplier.xci]
if {[llength $xci] != 1} {
    puts "ERROR: multiplier.xci was not generated uniquely"
    exit 4
}

puts "MULTIPLIER_XCI=$xci"
puts "MULTIPLIER_IPDEF=[get_property IPDEF [get_ips multiplier]]"
puts "MULTIPLIER_LOCKED=[get_property IS_LOCKED [get_ips multiplier]]"
puts "MULTIPLIER_SIM_MODEL=[get_property SELECTED_SIM_MODEL [get_ips multiplier]]"
close_project
exit 0
