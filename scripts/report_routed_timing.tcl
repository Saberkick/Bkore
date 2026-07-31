# Report detailed CPU timing from an existing routed checkpoint.
#
# Usage:
#   vivado -mode batch -source scripts/report_routed_timing.tcl \
#     -tclargs <routed.dcp> <report-directory> ?cpu-period-ns?

if {$argc < 2 || $argc > 3} {
    puts "Usage: report_routed_timing.tcl <routed.dcp> <report-directory> ?cpu-period-ns?"
    exit 2
}

set checkpoint [file normalize [lindex $argv 0]]
set report_dir [file normalize [lindex $argv 1]]
file mkdir $report_dir

open_checkpoint $checkpoint
set cpu_clocks [get_clocks -quiet cpu_clk]
if {[llength $cpu_clocks] != 1} {
    puts "ERROR: expected exactly one cpu_clk, got [llength $cpu_clocks]"
    exit 3
}

if {$argc == 3} {
    # Timing-only what-if analysis.  This does not change the checkpoint or
    # PLL XCI; it replaces the generated CPU clock in the open in-memory
    # design so the same routed netlist can be evaluated at a target period.
    set override_period [lindex $argv 2]
    set cpu_clock_pin [get_pins -quiet \
        fpga_pll.u_clk_pll/inst/plle2_adv_inst/CLKOUT0]
    if {[llength $cpu_clock_pin] != 1} {
        puts "ERROR: expected exactly one CPU PLL output pin"
        exit 4
    }
    # create_clock without -add replaces clocks already defined on the same
    # source object.  Vivado does not provide the Synopsys delete_clocks
    # command, so redefine cpu_clk directly on the PLL output pin.
    create_clock \
        -name cpu_clk \
        -period $override_period \
        -waveform [list 0.0 [expr {$override_period / 2.0}]] \
        $cpu_clock_pin
    set cpu_clocks [get_clocks cpu_clk]
    set_clock_groups -asynchronous \
        -group [get_clocks clk] \
        -group $cpu_clocks \
        -group [get_clocks sys_clk] \
        -group [get_clocks ddr_clk]
    puts "CPU_CLK_OVERRIDE=true"
}

puts "CPU_CLK_PERIOD=[get_property PERIOD $cpu_clocks]"
report_timing_summary \
    -delay_type min_max \
    -report_unconstrained \
    -check_timing_verbose \
    -max_paths 10 \
    -file [file join $report_dir timing_summary_routed.rpt]
report_timing \
    -group cpu_clk \
    -delay_type max \
    -max_paths 100 \
    -nworst 1 \
    -path_type full \
    -file [file join $report_dir cpu_clk_worst_100.rpt]
report_utilization \
    -hierarchical \
    -hierarchical_depth 5 \
    -file [file join $report_dir utilization_routed.rpt]

close_design
