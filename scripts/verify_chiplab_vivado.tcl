# Rebuild the chiplab project through placement and emit machine-checkable
# resource/DRC reports outside the generated Vivado project directory.
#
# Usage:
#   vivado -mode batch -source scripts/verify_chiplab_vivado.tcl \
#     -tclargs <loongson.xpr> <report-directory> ?jobs?

if {$argc < 2 || $argc > 3} {
    puts "Usage: verify_chiplab_vivado.tcl <project.xpr> <report-directory> ?jobs?"
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

# Keep an existing project in sync when Chisel starts emitting a newly split
# module beside DualBackend.sv.
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

open_run synth_1
report_utilization \
    -hierarchical \
    -hierarchical_depth 5 \
    -file [file join $report_dir synth_hier_util.rpt]
close_design

launch_runs impl_1 -to_step place_design -jobs $jobs
wait_on_run impl_1
set impl_status [get_property STATUS [get_runs impl_1]]
puts "IMPL_STATUS=$impl_status"
# When a run is intentionally stopped at place_design, Vivado reports the
# following (unstarted) step, for example "Not started phys_opt_design", rather
# than a generic "Complete" status.  Treat only an explicit failure as failure;
# open_run below is the authoritative check that the placed checkpoint exists.
if {[string match -nocase "*error*" $impl_status] ||
    [string match -nocase "*fail*" $impl_status]} {
    exit 4
}

open_run impl_1
report_utilization \
    -hierarchical \
    -hierarchical_depth 5 \
    -file [file join $report_dir placed_hier_util.rpt]
report_drc -file [file join $report_dir placed_drc.rpt]
close_design
close_project
