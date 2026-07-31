if {$argc != 3} {
    puts "Usage: vivado -mode batch -source install_chiplab_arithmetic_ips.tcl -tclargs <project.xpr> <multiplier.xci> <div_gen_0.xci>"
    exit 2
}

set project_file [file normalize [lindex $argv 0]]
set multiplier_xci [file normalize [lindex $argv 1]]
set divider_xci [file normalize [lindex $argv 2]]
foreach xci [list $multiplier_xci $divider_xci] {
    if {![file isfile $xci]} {
        puts "ERROR: arithmetic XCI does not exist: $xci"
        exit 3
    }
}

open_project $project_file

# Remove the temporary behavioral workaround and any former IP objects.
foreach model_pattern {*sim_models/multiplier_sim.sv *sim_models/div_gen_0_sim.sv} {
    set model_files [get_files -quiet -all $model_pattern]
    if {[llength $model_files] != 0} {
        remove_files $model_files
    }
}
foreach xci_pattern {*multiplier.xci *div_gen_0.xci} {
    set old_ip_files [get_files -quiet -all $xci_pattern]
    if {[llength $old_ip_files] != 0} {
        remove_files $old_ip_files
    }
}

add_files -norecurse [list $multiplier_xci $divider_xci]
set multiplier_file [get_files -quiet -all $multiplier_xci]
set divider_file [get_files -quiet -all $divider_xci]
if {[llength $multiplier_file] != 1 || [llength $divider_file] != 1} {
    puts "ERROR: multiplier/divider XCI files are not unique"
    close_project
    exit 4
}

set_property USED_IN {synthesis implementation simulation} $multiplier_file
set_property USED_IN {synthesis implementation simulation} $divider_file

generate_target all [get_ips multiplier]
generate_target all [get_ips div_gen_0]
foreach ip_name {multiplier div_gen_0} {
    export_ip_user_files \
        -of_objects [get_ips $ip_name] \
        -no_script \
        -sync \
        -force \
        -quiet
}

update_compile_order -fileset sources_1
update_compile_order -fileset sim_1

puts "MULTIPLIER_XCI=$multiplier_file"
puts "MULTIPLIER_LOCKED=[get_property IS_LOCKED [get_ips multiplier]]"
puts "DIVIDER_XCI=$divider_file"
puts "DIVIDER_LOCKED=[get_property IS_LOCKED [get_ips div_gen_0]]"

close_project
exit 0
