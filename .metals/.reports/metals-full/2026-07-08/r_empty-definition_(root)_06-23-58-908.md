error id: file://<WORKSPACE>/src/main/scala/mycpu/Top.scala:wb_target_pc
file://<WORKSPACE>/src/main/scala/mycpu/Top.scala
empty definition using pc, found symbol in pc: wb_target_pc
empty definition using semanticdb
empty definition using fallback
non-local guesses:
	 -chisel3/wb_stage/io/wb_target_pc.
	 -chisel3/wb_stage/io/wb_target_pc#
	 -chisel3/wb_stage/io/wb_target_pc().
	 -chisel3/util/wb_stage/io/wb_target_pc.
	 -chisel3/util/wb_stage/io/wb_target_pc#
	 -chisel3/util/wb_stage/io/wb_target_pc().
	 -wb_stage/io/wb_target_pc.
	 -wb_stage/io/wb_target_pc#
	 -wb_stage/io/wb_target_pc().
	 -scala/Predef.wb_stage.io.wb_target_pc.
	 -scala/Predef.wb_stage.io.wb_target_pc#
	 -scala/Predef.wb_stage.io.wb_target_pc().
offset: 2198
uri: file://<WORKSPACE>/src/main/scala/mycpu/Top.scala
text:
```scala
package mycpu

import chisel3._
import chisel3.util._

class mycpu_top extends RawModule {
    val clk             = IO(Input(Clock()))
    val resetn          = IO(Input(Bool()))

    // inst sram interface
    val inst_sram_req     = IO(Output(Bool()))
    val inst_sram_wr      = IO(Output(Bool()))
    val inst_sram_size    = IO(Output(UInt(2.W)))
    val inst_sram_wstrb   = IO(Output(UInt(4.W)))
    val inst_sram_addr    = IO(Output(UInt(32.W)))
    val inst_sram_wdata   = IO(Output(UInt(32.W)))
    val inst_sram_addr_ok = IO(Input(Bool()))
    val inst_sram_data_ok = IO(Input(Bool()))
    val inst_sram_rdata   = IO(Input(UInt(32.W)))

    // data sram interface
    val data_sram_req     = IO(Output(Bool()))
    val data_sram_wr      = IO(Output(Bool()))
    val data_sram_size    = IO(Output(UInt(2.W)))
    val data_sram_wstrb   = IO(Output(UInt(4.W)))
    val data_sram_addr    = IO(Output(UInt(32.W)))
    val data_sram_wdata   = IO(Output(UInt(32.W)))
    val data_sram_addr_ok = IO(Input(Bool()))
    val data_sram_data_ok = IO(Input(Bool()))
    val data_sram_rdata   = IO(Input(UInt(32.W)))

    // trace debug interface
    val debug_wb_pc       = IO(Output(UInt(32.W)))
    val debug_wb_rf_we    = IO(Output(UInt(4.W)))
    val debug_wb_rf_wnum  = IO(Output(UInt(5.W)))
    val debug_wb_rf_wdata = IO(Output(UInt(32.W)))
    val reset_high = ~resetn

    withClockAndReset(clk, reset_high) {

        // 1. 例化所有模块
        val ctrl = Module(new Ctrl())
        val timer = Module(new StableCounter())
        val regfile = Module(new Regfile())
        
        val if_stage  = Module(new StageIF())
        val id_stage  = Module(new StageID())
        val ex_stage  = Module(new StageEX())
        val mem_stage = Module(new StageMEM())
        val wb_stage  = Module(new StageWB())


        if_stage.io.out <> id_stage.io.in
        id_stage.io.out <> ex_stage.io.in
        ex_stage.io.out <> mem_stage.io.in
        mem_stage.io.out <> wb_stage.io.in

        ctrl.io.ex_branch_req := ex_stage.io.branch_req
        ctrl.io.ex_branch_pc  := ex_stage.io.branch_pc
        ctrl.io.wb_flush      := wb_stage.io.wb_flush
        ctrl.io.wb_target_pc  := wb_stage.io.wb_targe@@t_pc

        if_stage.io.flush           := ctrl.io.flush_if
        if_stage.io.flush_target_pc := ctrl.io.next_pc
        id_stage.io.flush           := ctrl.io.flush_id
        ex_stage.io.flush           := ctrl.io.flush_ex
        mem_stage.io.flush          := ctrl.io.flush_mem
        // WB 没有 flush 输入口

        // 4. 旁路前推网络 (Forwarding)
        id_stage.io.fwdFromEx  := ex_stage.io.fwdOut
        id_stage.io.fwdFromMem := mem_stage.io.fwdOut
        
        ex_stage.io.fwdFromMem := mem_stage.io.fwdOut
        ex_stage.io.fwdFromWb  := wb_stage.io.fwdOut

        // 5. 异常预警防线 (MEM 到 EX 的跨级防御)
        ex_stage.io.mem_has_exc_in := mem_stage.io.mem_has_exc_out

        // 6. 中断与定时器路由
        id_stage.io.has_int   := wb_stage.io.wb_has_int
        wb_stage.io.hw_int_in := 0.U(8.W)
        ex_stage.io.timer_in  := timer.io.timer_out

        // 7. 寄存器堆 (RegFile) 连线
        regfile.io.raddr1 := id_stage.io.rf_raddr1
        id_stage.io.rf_rdata1 := regfile.io.rdata1
        
        regfile.io.raddr2 := id_stage.io.rf_raddr2
        id_stage.io.rf_rdata2 := regfile.io.rdata2
        
        regfile.io.we     := wb_stage.io.rf_we
        regfile.io.waddr  := wb_stage.io.rf_waddr
        regfile.io.wdata  := wb_stage.io.rf_wdata

        // 8. 外部 SRAM 接口连线
        if_adapter.io.imem <> if_stage.io.imem
        
        inst_sram_en    := if_adapter.io.sram.en
        inst_sram_we    := if_adapter.io.sram.we
        inst_sram_addr  := if_adapter.io.sram.addr
        inst_sram_wdata := if_adapter.io.sram.wdata
        if_adapter.io.sram.rdata := inst_sram_rdata

        data_sram_en    := ex_stage.io.data_sram.en
        data_sram_we    := ex_stage.io.data_sram.we
        data_sram_addr  := ex_stage.io.data_sram.addr
        data_sram_wdata := ex_stage.io.data_sram.wdata
        
        // 把读回来的数据给 EX 的 bundle 填坑（防止 Chisel 报未初始化错误）
        ex_stage.io.data_sram.rdata := data_sram_rdata 
        
        // 【补上这行致命连线！】把数据真正送到 MEM 级去清洗
        mem_stage.io.sram_rdata     := data_sram_rdata

        // 9. Debug 接口连线
        debug_wb_pc       := wb_stage.io.debug_wb_pc
        debug_wb_rf_we    := wb_stage.io.debug_wb_rf_we
        debug_wb_rf_wnum  := wb_stage.io.debug_wb_rf_wnum
        debug_wb_rf_wdata := wb_stage.io.debug_wb_rf_wdata
    }
}
```


#### Short summary: 

empty definition using pc, found symbol in pc: wb_target_pc