# Usage:
#   vivado -mode batch -source scripts/report_hier_util.tcl \
#     -tclargs <checkpoint.dcp> <report.rpt> ?hierarchical-depth?

if {$argc < 2 || $argc > 3} {
    puts "Usage: report_hier_util.tcl <checkpoint.dcp> <report.rpt> ?hierarchical-depth?"
    exit 2
}

set checkpoint [file normalize [lindex $argv 0]]
set report_file [file normalize [lindex $argv 1]]
set hierarchy_depth 4
if {$argc == 3} {
    set hierarchy_depth [lindex $argv 2]
}

open_checkpoint $checkpoint
report_utilization \
    -hierarchical \
    -hierarchical_depth $hierarchy_depth \
    -file $report_file
close_design
