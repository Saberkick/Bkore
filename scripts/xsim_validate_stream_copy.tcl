proc value_of {path} {
    set object [get_objects -quiet $path]
    if {[llength $object] == 0} {
        return "<not-found>"
    }
    return [get_value -radix hex [lindex $object 0]]
}

restart
foreach duration {5us 15us 30us 50us 100us 200us 300us 300us} {
    run $duration
    puts "STREAM_COPY_CHECK time=[current_time] pc=[value_of /tb_top/debug_wb_pc] wen=[value_of /tb_top/debug_wb_rf_wen] frontend_pc=[value_of /tb_top/u_soc_top/u_cpu/frontend/pc] frontend_wait=[value_of /tb_top/u_soc_top/u_cpu/frontend/waitResponse] wb0=[value_of /tb_top/u_soc_top/u_cpu/backend/wbValidBits_0] wb1=[value_of /tb_top/u_soc_top/u_cpu/backend/wbValidBits_1]"
}
quit
