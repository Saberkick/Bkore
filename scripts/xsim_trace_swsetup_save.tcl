proc value_of {path} {
    set object [get_objects -quiet $path]
    if {[llength $object] == 0} {
        return "<not-found>"
    }
    return [get_value -radix hex [lindex $object 0]]
}

set be /tb_top/u_soc_top/u_cpu/backend
set found 0
restart
run 2500us
for {set i 0} {$i < 19000} {incr i} {
    run 10ns
    set ex_pc [value_of $be/rrdExQueue/io_deq_bits_lane_0_pipe_pc]
    set m1_pc [value_of $be/exAgQueue/io_deq_bits_lane_0_pipe_pc]
    set m2_pc [value_of $be/m1M2Queue/io_deq_bits_lane_0_pipe_pc]
    set wb_pc [value_of $be/wbPayload_lane_0_pipe_pc]
    set interesting [expr {
        $ex_pc eq "1c0051b8" || $m1_pc eq "1c0051b8" ||
        $m2_pc eq "1c0051b8" || $wb_pc eq "1c0051b8"}]
    if {$interesting} {
        set found 1
        puts [join [list \
            "SAVE_TRACE time=[current_time]" \
            "r1=[value_of $be/regfile/regs_1]" \
            "sp=[value_of $be/regfile/regs_3]" \
            "ex_v=[value_of $be/rrdExQueue/io_deq_valid]" \
            "ex_pc=$ex_pc" \
            "ex_src1=[value_of $be/rrdExQueue/io_deq_bits_lane_0_pipe_src1_value]" \
            "ex_src2=[value_of $be/rrdExQueue/io_deq_bits_lane_0_pipe_src2_value]" \
            "m1_v=[value_of $be/exAgQueue/io_deq_valid]" \
            "m1_pc=$m1_pc" \
            "m1_src1=[value_of $be/exAgQueue/io_deq_bits_lane_0_pipe_src1_value]" \
            "m1_src2=[value_of $be/exAgQueue/io_deq_bits_lane_0_pipe_src2_value]" \
            "prepared_valid=[value_of $be/m1PreparedValid]" \
            "prepared_addr=[value_of $be/m1PreparedPa]" \
            "prepared_data=[value_of $be/m1PreparedStoreData]" \
            "sb_enq_valid=[value_of $be/storeBuffer/io_enq_valid]" \
            "sb_enq_ready=[value_of $be/storeBuffer/io_enq_ready]" \
            "sb_addr=[value_of $be/storeBuffer/io_enq_bits_address]" \
            "sb_data=[value_of $be/storeBuffer/io_enq_bits_data]" \
            "sb_mask=[value_of $be/storeBuffer/io_enq_bits_mask]" \
            "sb_count=[value_of $be/storeBuffer/count]" \
            "m2_pc=$m2_pc" \
            "wb_v=[value_of $be/wbValidBits_0]" \
            "wb_pc=$wb_pc"] " "]
    }
    if {$found && $wb_pc eq "1c0051b8"} {
        run 100ns
        break
    }
}
if {!$found} {
    puts "SAVE_TRACE_ERROR prologue store was not found"
}
close_sim
