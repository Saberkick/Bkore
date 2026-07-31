proc v {path} {
    set object [get_objects -quiet $path]
    if {[llength $object] == 0} {
        return "-"
    }
    if {[catch {get_value -radix hex [lindex $object 0]} value]} {
        return "?"
    }
    return $value
}

restart
run 3860 ns
puts "TRACE_COLUMNS=time,clk,rvalid,rdata,front_resp,front_q_count,front_q_inst,front_out0,backend_pop,decode_valid0,decode_inst,select_valid,select_ok,select_inst,id_count,ex_count,ag_count,m2_count,m2_fire,wb0,wb_inst,wb_exc,wb_ertn,wb_refetch,wb_flush"
for {set cycle 0} {$cycle < 28} {incr cycle} {
    run 5 ns
    set base /tb_top/u_soc_top/u_cpu
    puts "TRACE=[current_time],[v /tb_top/clk],[v /tb_top/u_soc_top/cpu_rvalid],[v /tb_top/u_soc_top/cpu_rdata],[v $base/frontend/realResponse],[v $base/frontend/queue/count],[v $base/frontend/queue/entries_0_inst],[v $base/_frontend_io_outValid_0],[v $base/_backend_io_popCount],[v $base/backend/decodeQueue/valid_0],[v $base/backend/decodeQueue/entries_0_pipe_inst],[v $base/backend/selectValid],[v $base/backend/selectApproved],[v $base/backend/selectPayload_lane_0_pipe_inst],[v $base/backend/idRrdQueue/count],[v $base/backend/rrdExQueue/count],[v $base/backend/exAgQueue/count],[v $base/backend/m1M2Queue/count],[v $base/backend/m2Fire],[v $base/backend/wbValidBits_0],[v $base/backend/wbPayload_lane_0_pipe_inst],[v $base/backend/wbPayload_lane_0_pipe_hasException],[v $base/backend/wbPayload_lane_0_pipe_inst_ertn],[v $base/backend/wbPayload_lane_0_pipe_is_refetch],[v $base/backend/wbFlush]"
}
quit
