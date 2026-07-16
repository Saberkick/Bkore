# Vivado 2025.1 / 2026.1: create the Divider Generator used by MDU.scala.
# Source this file from the Tcl Console of the target RTL project:
#   source D:/Develop/CPU/Archive/mycpu/src/main/scala/mycpu/create_div_gen_0.tcl

set ip_name div_gen_0

# Frequency-first default. Because MDU.scala implements both input tready
# handshakes, this can later be changed to 2, 4, or 8 without Scala changes.
set clocks_per_division 1

set vivado_version [version -short]
if {![string match "2025.1*" $vivado_version] &&
    ![string match "2026.1*" $vivado_version]} {
    puts "WARNING: This script was verified for the 2025.1/2026.1 IP flow; current Vivado is $vivado_version."
}

if {[llength [get_projects -quiet]] == 0} {
    error "Open the target RTL project before sourcing create_div_gen_0.tcl."
}

# The legacy Divider Generator can retain the xilinx.com vendor name in newer
# Vivado releases. Resolve the installed VLNV instead of assuming the vendor.
set div_defs [get_ipdefs -all -quiet "*:ip:div_gen:5.1"]
if {[llength $div_defs] == 0} {
    error "Divider Generator v5.1 is unavailable. Check the selected part and run: get_ipdefs -all *:ip:div_gen:*"
}
set div_vlnv [lindex $div_defs 0]

set ip [get_ips -quiet $ip_name]
if {[llength $ip] == 0} {
    set vlnv_fields [split $div_vlnv ":"]
    create_ip -name    [lindex $vlnv_fields 2] \
              -vendor  [lindex $vlnv_fields 0] \
              -library [lindex $vlnv_fields 1] \
              -version [lindex $vlnv_fields 3] \
              -module_name $ip_name
    set ip [get_ips $ip_name]
} else {
    puts "INFO: Reconfiguring existing IP $ip_name."
}

# CONFIG property spelling/capitalization has varied in generated scripts.
# Resolve it case-insensitively so the same file works in both requested tools.
proc set_div_config {ip_object user_parameter value} {
    set requested "CONFIG.$user_parameter"
    set actual ""
    foreach property_name [list_property $ip_object] {
        if {[string equal -nocase $property_name $requested]} {
            set actual $property_name
            break
        }
    }
    if {$actual eq ""} {
        error "Divider Generator does not expose $requested in this Vivado/IP version."
    }
    set_property $actual $value $ip_object
}

set_div_config $ip algorithm_type                 Radix2
set_div_config $ip dividend_and_quotient_width    32
set_div_config $ip divisor_width                  32
set_div_config $ip remainder_type                 Remainder
set_div_config $ip fractional_width               32
set_div_config $ip operand_sign                   Unsigned
set_div_config $ip clocks_per_division            $clocks_per_division
set_div_config $ip divide_by_zero_detect          false

# Blocking mode exposes independent input tready signals. The output has no
# tready because the Scala wrapper captures every one-cycle result pulse.
set_div_config $ip flowcontrol                    Blocking
set_div_config $ip optimizegoal                   Performance
set_div_config $ip outtready                      false

set_div_config $ip dividend_has_tuser             false
set_div_config $ip dividend_has_tlast             false
set_div_config $ip divisor_has_tuser              false
set_div_config $ip divisor_has_tlast              false
set_div_config $ip latency_configuration          Automatic
set_div_config $ip aclken                         false
set_div_config $ip aresetn                        true

generate_target all $ip

if {[llength [get_runs -quiet ${ip_name}_synth_1]] == 0} {
    create_ip_run $ip
}

puts "INFO: $ip_name generated with Divider Generator $div_vlnv."
puts "INFO: Radix2, unsigned 32/32, quotient+remainder, Blocking AXIS, clocks_per_division=$clocks_per_division."
puts "INFO: Run '${ip_name}_synth_1' (or synthesize the top) and inspect report_timing_summary/report_utilization."
