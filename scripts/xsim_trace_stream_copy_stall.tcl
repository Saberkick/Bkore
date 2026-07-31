proc sample {label path} {
    set object [get_objects -quiet $path]
    if {[llength $object] == 0} {
        puts "STALL $label=<not-found> PATH=$path"
    } else {
        puts "STALL $label=[get_value -radix hex [lindex $object 0]] PATH=$path"
    }
}

restart
run 1 ms
set base /tb_top/u_soc_top/u_cpu
foreach {label path} {
    debug_pc          /tb_top/debug_wb_pc
    debug_wen         /tb_top/debug_wb_rf_wen
    frontend_pc       /tb_top/u_soc_top/u_cpu/frontend/pc
    frontend_f1       /tb_top/u_soc_top/u_cpu/frontend/f1Valid
    frontend_wait     /tb_top/u_soc_top/u_cpu/frontend/waitResponse
    frontend_qcount   /tb_top/u_soc_top/u_cpu/frontend/queue/count
    frontend_out0     /tb_top/u_soc_top/u_cpu/_frontend_io_outValid_0
    frontend_outpc    /tb_top/u_soc_top/u_cpu/_frontend_io_outBits_0_pc
    frontend_outinst  /tb_top/u_soc_top/u_cpu/_frontend_io_outBits_0_inst
    backend_pop       /tb_top/u_soc_top/u_cpu/_backend_io_popCount
    decode_valid0     /tb_top/u_soc_top/u_cpu/backend/decodeQueue/valid_0
    decode_valid1     /tb_top/u_soc_top/u_cpu/backend/decodeQueue/valid_1
    decode_inst0      /tb_top/u_soc_top/u_cpu/backend/decodeQueue/entries_0_pipe_inst
    decode_pc0        /tb_top/u_soc_top/u_cpu/backend/decodeQueue/entries_0_pipe_pc
    select_valid      /tb_top/u_soc_top/u_cpu/backend/selectValid
    select_approved   /tb_top/u_soc_top/u_cpu/backend/selectApproved
    select_pc         /tb_top/u_soc_top/u_cpu/backend/selectPayload_lane_0_pipe_pc
    select_inst       /tb_top/u_soc_top/u_cpu/backend/selectPayload_lane_0_pipe_inst
    select_serial     /tb_top/u_soc_top/u_cpu/backend/selectPayload_lane_0_serializing
    select_src1read   /tb_top/u_soc_top/u_cpu/backend/selectPayload_lane_0_src1Read
    select_src1       /tb_top/u_soc_top/u_cpu/backend/selectPayload_lane_0_pipe_src1_addr
    select_src2read   /tb_top/u_soc_top/u_cpu/backend/selectPayload_lane_0_src2Read
    select_src2       /tb_top/u_soc_top/u_cpu/backend/selectPayload_lane_0_pipe_src2_addr
    scoreboard_b0     /tb_top/u_soc_top/u_cpu/backend/scoreboardBlocked_0
    scoreboard_b1     /tb_top/u_soc_top/u_cpu/backend/scoreboardBlocked_1
    scoreboard_b2     /tb_top/u_soc_top/u_cpu/backend/scoreboardBlocked_2
    scoreboard_b3     /tb_top/u_soc_top/u_cpu/backend/scoreboardBlocked_3
    serial_inflight   /tb_top/u_soc_top/u_cpu/backend/serialInFlight
    serial_blocked    /tb_top/u_soc_top/u_cpu/backend/serialScoreboardBlocked
    scoreboard_ready  /tb_top/u_soc_top/u_cpu/backend/scoreboardReady
    older_work        /tb_top/u_soc_top/u_cpu/backend/olderWorkPresent
    id_count          /tb_top/u_soc_top/u_cpu/backend/idRrdQueue/count
    ex_count          /tb_top/u_soc_top/u_cpu/backend/rrdExQueue/count
    ag_count          /tb_top/u_soc_top/u_cpu/backend/exAgQueue/count
    m2_count          /tb_top/u_soc_top/u_cpu/backend/m1M2Queue/count
    rrd_valid         /tb_top/u_soc_top/u_cpu/backend/rrdValid
    ex_valid          /tb_top/u_soc_top/u_cpu/backend/exValid
    m1_valid          /tb_top/u_soc_top/u_cpu/backend/m1Valid
    m2_valid          /tb_top/u_soc_top/u_cpu/backend/m2Valid
    wb0               /tb_top/u_soc_top/u_cpu/backend/wbValidBits_0
    wb1               /tb_top/u_soc_top/u_cpu/backend/wbValidBits_1
    wb_flush          /tb_top/u_soc_top/u_cpu/backend/wbFlush
    branch_redirect   /tb_top/u_soc_top/u_cpu/backend/branchRedirect
    m1_prepare_needed /tb_top/u_soc_top/u_cpu/backend/m1PrepareNeeded
    m1_prepared       /tb_top/u_soc_top/u_cpu/backend/m1PreparedValid
    m1_dcache_issued  /tb_top/u_soc_top/u_cpu/backend/m1DcacheIssued
    dcache_outstanding /tb_top/u_soc_top/u_cpu/backend/dcacheOutstanding
    dcache_state      /tb_top/u_soc_top/u_cpu/dcache/state
    store_empty       /tb_top/u_soc_top/u_cpu/backend/storeBuffer/io_empty
    store_count       /tb_top/u_soc_top/u_cpu/backend/storeBuffer/count
    div_active        /tb_top/u_soc_top/u_cpu/backend/divider/active
    div_started       /tb_top/u_soc_top/u_cpu/backend/divStarted
    div_finished      /tb_top/u_soc_top/u_cpu/backend/divFinished
} {
    sample $label $path
}
quit
