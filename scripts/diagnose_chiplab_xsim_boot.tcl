if {$argc != 1} {
    puts "Usage: vivado -mode batch -source diagnose_chiplab_xsim_boot.tcl -tclargs <project.xpr>"
    exit 2
}

set project_file [file normalize [lindex $argv 0]]
set project_dir [file dirname $project_file]
set image_file [file normalize [file join $project_dir .. inst_data.bin]]
if {![file isfile $image_file]} {
    puts "ERROR: simulation image is missing: $image_file"
    exit 3
}
puts "BOOT_IMAGE=$image_file"
puts "BOOT_IMAGE_BYTES=[file size $image_file]"

open_project $project_file
launch_simulation -simset sim_1 -mode behavioral
restart

proc sample_value {label path} {
    set objects [get_objects -quiet $path]
    if {[llength $objects] == 0} {
        puts "SAMPLE $label=<not-found> PATH=$path"
    } else {
        if {[catch {set value [get_value -radix hex [lindex $objects 0]]} message]} {
            puts "SAMPLE $label=<read-error:$message> PATH=$path"
        } else {
            puts "SAMPLE $label=$value PATH=$path"
        }
    }
}

proc sample_boot {tag} {
    puts "BOOT_SAMPLE_BEGIN=$tag SIM_TIME=[current_time]"
    foreach {label path} {
        tb_resetn       /tb_top/resetn
        cpu_resetn      /tb_top/u_soc_top/cpu_resetn
        cpu_arvalid     /tb_top/u_soc_top/cpu_arvalid
        cpu_arready     /tb_top/u_soc_top/cpu_arready
        cpu_araddr      /tb_top/u_soc_top/cpu_araddr
        cpu_rvalid      /tb_top/u_soc_top/cpu_rvalid
        cpu_rready      /tb_top/u_soc_top/cpu_rready
        cpu_rdata       /tb_top/u_soc_top/cpu_rdata
        debug_pc        /tb_top/debug_wb_pc
        debug_wen       /tb_top/debug_wb_rf_wen
        frontend_pc     /tb_top/u_soc_top/u_cpu/frontend/pc
        frontend_f1     /tb_top/u_soc_top/u_cpu/frontend/f1Valid
        frontend_wait   /tb_top/u_soc_top/u_cpu/frontend/waitResponse
        frontend_exc    /tb_top/u_soc_top/u_cpu/frontend/f1Exception
        frontend_fire   /tb_top/u_soc_top/u_cpu/frontend/requestFire
        frontend_resp   /tb_top/u_soc_top/u_cpu/frontend/realResponse
        frontend_source /tb_top/u_soc_top/u_cpu/frontend/sourceCount
        frontend_out0   /tb_top/u_soc_top/u_cpu/_frontend_io_outValid_0
        frontend_qcount /tb_top/u_soc_top/u_cpu/frontend/queue/count
        icache_state    /tb_top/u_soc_top/u_cpu/icache/state
        icache_dataok   /tb_top/u_soc_top/u_cpu/_icache_io_cpu_dataOk
        icache_retvalid /tb_top/u_soc_top/u_cpu/icache/io_axi_ret_valid
        icache_retlast  /tb_top/u_soc_top/u_cpu/icache/io_axi_ret_last
        backend_flush   /tb_top/u_soc_top/u_cpu/_backend_io_frontendFlush
        backend_pop     /tb_top/u_soc_top/u_cpu/_backend_io_popCount
        backend_wb0     /tb_top/u_soc_top/u_cpu/backend/wbValidBits_0
        backend_wb1     /tb_top/u_soc_top/u_cpu/backend/wbValidBits_1
    } {
        sample_value $label $path
    }
    puts "BOOT_SAMPLE_END=$tag"
}

run 1900 ns
sample_boot before_reset_release
for {set cycle 0} {$cycle < 360} {incr cycle} {
    run 10 ns
    if {$cycle == 29} {
        sample_boot after_reset_release
    }
    if {$cycle == 79} {
        sample_boot after_2700ns
    }
    if {$cycle == 159} {
        sample_boot after_3500ns
    }
    if {$cycle >= 199 && ($cycle % 20) == 19} {
        sample_boot "late_cycle_$cycle"
    }
}

close_sim
close_project
exit 0
