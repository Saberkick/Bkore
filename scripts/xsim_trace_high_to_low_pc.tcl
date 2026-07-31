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
set csr $be/csr

proc trace_state {label} {
    global tb cpu be csr
    puts [join [list \
        $label \
        "time=[current_time]" \
        "debug_pc=[value_of $tb/debug_wb_pc]" \
        "front_pc=[value_of $cpu/frontend/pc]" \
        "branchEvent=[value_of $be/branchEvent]" \
        "branchRedirect=[value_of $be/branchRedirect]" \
        "branch_pc=[value_of $be/branchPipe_pc]" \
        "branch_target=[value_of $be/branchResolvedTarget]" \
        "wbFlush=[value_of $be/wbFlush]" \
        "fault_pc=[value_of $be/csr_io_excPc]" \
        "fault_ecode=[value_of $be/csr_io_excEcode]" \
        "eentry_out=[value_of $be/_csr_io_eentryOut]" \
        "tlbrentry_out=[value_of $be/_csr_io_tlbrentryOut]" \
        "ecode=[value_of $csr/estat_ecode]" \
        "esubcode=[value_of $csr/estat_esubcode]" \
        "era=[value_of $csr/eraReg]" \
        "eentry=[value_of $csr/eentry_va]" \
        "r12=[value_of $be/regfile/regs_12]" \
        "r13=[value_of $be/regfile/regs_13]" \
        "r14=[value_of $be/regfile/regs_14]" \
        "select_pc=[value_of $be/selectPayload_lane_0_pipe_pc]" \
        "select_inst=[value_of $be/selectPayload_lane_0_pipe_inst]" \
        "selectValid=[value_of $be/selectValid]" \
        "selectApproved=[value_of $be/selectApproved]"] " "]
}

restart
run 2680us
trace_state "FINE_BEGIN"
set previous_pc [value_of $cpu/frontend/pc]
for {set i 0} {$i < 2500} {incr i} {
    run 10ns
    set pc [value_of $cpu/frontend/pc]
    set redirect [value_of $be/branchRedirect]
    set flush [value_of $be/wbFlush]
    if {$redirect eq "1" || $flush eq "1" ||
        ([string match "1c*" $previous_pc] && ![string match "1c*" $pc])} {
        trace_state "FINE_EVENT"
    }
    if {[string match "1c*" $previous_pc] && ![string match "1c*" $pc]} {
        break
    }
    set previous_pc $pc
}

close_sim
