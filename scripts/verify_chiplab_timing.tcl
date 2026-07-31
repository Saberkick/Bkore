# Rebuild the chiplab project through routing and emit detailed CPU timing
# reports.  The project clock configuration is used as-is.
#
# Usage:
#   vivado -mode batch -source scripts/verify_chiplab_timing.tcl \
#     -tclargs <loongson.xpr> <report-directory> ?jobs?

if {$argc < 2 || $argc > 3} {
    puts "Usage: verify_chiplab_timing.tcl <project.xpr> <report-directory> ?jobs?"
    exit 2
}

set project_file [file normalize [lindex $argv 0]]
set report_dir [file normalize [lindex $argv 1]]
set jobs 8
if {$argc == 3} {
    set jobs [lindex $argv 2]
}
file mkdir $report_dir

open_project $project_file

# A previously-created project does not automatically discover a new
# split-Verilog module copied into the existing CPU source directory.  Locate
# it relative to the already registered DualBackend source and add it when
# present.  Fresh projects that scan the directory already contain the file.
set backend_rtl [get_files -quiet *DualBackend.sv]
if {[llength $backend_rtl] == 1} {
    set decode_queue_rtl [file normalize [file join \
        [file dirname [lindex $backend_rtl 0]] DualDecodeQueue.sv]]
    if {[file exists $decode_queue_rtl] &&
        [llength [get_files -quiet $decode_queue_rtl]] == 0} {
        puts "ADDING_SOURCE=$decode_queue_rtl"
        add_files -norecurse -fileset sources_1 $decode_queue_rtl
        update_compile_order -fileset sources_1
    }
}

reset_run synth_1
launch_runs synth_1 -jobs $jobs
wait_on_run synth_1
set synth_status [get_property STATUS [get_runs synth_1]]
puts "SYNTH_STATUS=$synth_status"
if {![string match "*Complete*" $synth_status]} {
    exit 3
}

launch_runs impl_1 -to_step route_design -jobs $jobs
wait_on_run impl_1
set impl_status [get_property STATUS [get_runs impl_1]]
puts "IMPL_STATUS=$impl_status"
# "route_design Complete, Failed Timing!" is a valid result for this timing
# investigation: the routed checkpoint and detailed reports are exactly what
# we need for the next RTL iteration.  Reject only an execution error/cancel.
if {[string match -nocase "*error*" $impl_status] ||
    [string match -nocase "*cancel*" $impl_status]} {
    exit 4
}

open_run impl_1
set cpu_clocks [get_clocks -quiet cpu_clk]
if {[llength $cpu_clocks] != 1} {
    puts "ERROR: expected exactly one cpu_clk, got [llength $cpu_clocks]"
    exit 5
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
report_drc -file [file join $report_dir drc_routed.rpt]

close_design
close_project
