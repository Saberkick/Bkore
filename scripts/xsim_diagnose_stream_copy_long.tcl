proc value_of {path} {
    set object [get_objects -quiet $path]
    if {[llength $object] == 0} {
        return "<not-found>"
    }
    return [get_value -radix hex [lindex $object 0]]
}

proc sample_progress {} {
    set tb /tb_top
    set cpu $tb/u_soc_top/u_cpu
    set be $cpu/backend
    puts [join [list \
        "LONG_CHECK time=[current_time]" \
        "pc=[value_of $tb/debug_wb_pc]" \
        "front_pc=[value_of $cpu/frontend/pc]" \
        "front_wait=[value_of $cpu/frontend/waitResponse]" \
        "wb0=[value_of $be/wbValidBits_0]" \
        "wb1=[value_of $be/wbValidBits_1]" \
        "selectValid=[value_of $be/selectValid]" \
        "selectApproved=[value_of $be/selectApproved]" \
        "serial=[value_of $be/serialInFlight]" \
        "r4=[value_of $be/regfile/regs_4]" \
        "r5=[value_of $be/regfile/regs_5]" \
        "r6=[value_of $be/regfile/regs_6]" \
        "r7=[value_of $be/regfile/regs_7]" \
        "r14=[value_of $be/regfile/regs_14]" \
        "r15=[value_of $be/regfile/regs_15]" \
        "r23=[value_of $be/regfile/regs_23]" \
        "r24=[value_of $be/regfile/regs_24]" \
        "r28=[value_of $be/regfile/regs_28]" \
        "r29=[value_of $be/regfile/regs_29]" \
        "dcache_state=[value_of $cpu/dcache/state]" \
        "dcache_refill=[value_of $cpu/dcache/refillCount]" \
        "ar_state=[value_of $cpu/bridge/ar_state]" \
        "w_state=[value_of $cpu/bridge/w_state]" \
        "test_end=[value_of $tb/test_end]" \
        "debug_end=[value_of $tb/debug_end]"] " "]
}

restart
foreach delta {1ms 1ms 1ms 1ms 1ms 1ms 1ms 1ms 1ms 1ms 5ms 5ms} {
    run $delta
    sample_progress
}
quit
