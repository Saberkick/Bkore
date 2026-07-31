proc value_of {path} {
    set object [get_objects -quiet $path]
    if {[llength $object] == 0} {
        return "<not-found>"
    }
    return [get_value -radix hex [lindex $object 0]]
}

set tb /tb_top
set cpu $tb/u_soc_top/u_cpu
set be $cpu/backend

proc return_state {} {
    global tb cpu be
    puts [join [list \
        "RETURN_TRACE time=[current_time]" \
        "front=[value_of $cpu/frontend/pc]" \
        "r1=[value_of $be/regfile/regs_1]" \
        "sp=[value_of $be/regfile/regs_3]" \
        "redirect=[value_of $be/branchRedirect]" \
        "branch_pc=[value_of $be/branchPipe_pc]" \
        "branch_target=[value_of $be/branchResolvedTarget]" \
        "id_v=[value_of $be/idRrdQueue/io_deq_valid]" \
        "id_pc0=[value_of $be/idRrdQueue/io_deq_bits_lane_0_pipe_pc]" \
        "id_pc1=[value_of $be/idRrdQueue/io_deq_bits_lane_1_pipe_pc]" \
        "id_src1_0=[value_of $be/idRrdQueue/io_deq_bits_lane_0_pipe_src1_addr]" \
        "id_src1_1=[value_of $be/idRrdQueue/io_deq_bits_lane_1_pipe_src1_addr]" \
        "id_val1_0=[value_of $be/idRrdQueue/io_deq_bits_lane_0_pipe_src1_value]" \
        "id_val1_1=[value_of $be/idRrdQueue/io_deq_bits_lane_1_pipe_src1_value]" \
        "ex_v=[value_of $be/rrdExQueue/io_deq_valid]" \
        "ex_pc0=[value_of $be/rrdExQueue/io_deq_bits_lane_0_pipe_pc]" \
        "ex_pc1=[value_of $be/rrdExQueue/io_deq_bits_lane_1_pipe_pc]" \
        "ex_src1_0=[value_of $be/rrdExQueue/io_deq_bits_lane_0_pipe_src1_value]" \
        "ex_src1_1=[value_of $be/rrdExQueue/io_deq_bits_lane_1_pipe_src1_value]" \
        "m1_v=[value_of $be/exAgQueue/io_deq_valid]" \
        "m1_pc0=[value_of $be/exAgQueue/io_deq_bits_lane_0_pipe_pc]" \
        "m1_pc1=[value_of $be/exAgQueue/io_deq_bits_lane_1_pipe_pc]" \
        "m1_res0=[value_of $be/exAgQueue/io_deq_bits_lane_0_pipe_ex_result]" \
        "m1_res1=[value_of $be/exAgQueue/io_deq_bits_lane_1_pipe_ex_result]" \
        "m2_v=[value_of $be/m1M2Queue/io_deq_valid]" \
        "m2_pc0=[value_of $be/m1M2Queue/io_deq_bits_lane_0_pipe_pc]" \
        "m2_pc1=[value_of $be/m1M2Queue/io_deq_bits_lane_1_pipe_pc]" \
        "m2_res0=[value_of $be/m1M2Queue/io_deq_bits_lane_0_pipe_ex_result]" \
        "m2_res1=[value_of $be/m1M2Queue/io_deq_bits_lane_1_pipe_ex_result]" \
        "wb_v0=[value_of $be/wbValidBits_0]" \
        "wb_v1=[value_of $be/wbValidBits_1]" \
        "wb_pc0=[value_of $be/wbPayload_lane_0_pipe_pc]" \
        "wb_pc1=[value_of $be/wbPayload_lane_1_pipe_pc]" \
        "wb_dst0=[value_of $be/wbPayload_lane_0_pipe_destReg]" \
        "wb_dst1=[value_of $be/wbPayload_lane_1_pipe_destReg]" \
        "wb_data0=[value_of $be/wbFinalData_0]" \
        "wb_data1=[value_of $be/wbFinalData_1]"] " "]
}

restart
run 2690us
for {set i 0} {$i < 60} {incr i} {
    run 10ns
    return_state
}
close_sim
