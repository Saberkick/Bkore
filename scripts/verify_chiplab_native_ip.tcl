if {$argc != 1} {
    puts "Usage: vivado -mode batch -source verify_chiplab_native_ip.tcl -tclargs <project.xpr>"
    exit 2
}

set project_file [file normalize [lindex $argv 0]]
open_project $project_file

foreach ip_name {multiplier div_gen_0} {
    set ip [get_ips -quiet $ip_name]
    if {[llength $ip] != 1} {
        puts "ERROR: expected one $ip_name IP, got [llength $ip]"
        close_project
        exit 3
    }
    if {[get_property IS_LOCKED $ip]} {
        puts "ERROR: $ip_name is locked"
        close_project
        exit 4
    }
    puts "NATIVE_IP=$ip_name IPDEF=[get_property IPDEF $ip]"
}

reset_simulation -simset sim_1 -mode behavioral
launch_simulation -simset sim_1 -mode behavioral -step compile
puts "NATIVE_XSIM_COMPILE_OK=1"
launch_simulation -simset sim_1 -mode behavioral -step elaborate
puts "NATIVE_XSIM_ELABORATE_OK=1"

close_project
exit 0
