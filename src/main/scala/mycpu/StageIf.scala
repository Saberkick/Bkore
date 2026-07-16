package mycpu

import chisel3._
import chisel3.util._

class StageIF extends Module {
    val io = IO(new Bundle {
        //For Stage ID
        val out             = Decoupled(new PipelineData())

        val flush           = Input(Bool())
        val flush_target_pc = Input(UInt(32.W))
        val bp_update       = Input(new BranchPredictorUpdate())
        //For mem
        val inst_sram       = new SramIo()
        val inst_uncached   = Output(Bool())


        val mmu_config   = Input(new MmuConfig())
        val tlb_s0_vppn     = Output(UInt(19.W))
        val tlb_s0_va_bit12 = Output(Bool())
        val tlb_s0_asid     = Output(UInt(10.W))
        val tlb_s0_found    = Input(Bool())
        val tlb_s0_ppn      = Input(UInt(20.W))
        val tlb_s0_ps       = Input(UInt(6.W))
        val tlb_s0_plv      = Input(UInt(2.W)) // 新增：用于 Stage 3 取指页特权不合规(PPI)判定
        val tlb_s0_mat      = Input(UInt(2.W)) // 新增：存储类型（当前可不处理，留作接口）
        val tlb_s0_v        = Input(Bool())    // 用于判定取指页无效(PIF)
    })

    // IF 职责：保存下一取指 PC，完成 DMW/TLB 地址翻译与取指异常判定，
    // 再通过类 SRAM 握手访问 ICache。前端最多保留一个未返回请求，但允许在旧响应
    // 被 ID 接收的同一拍发出下一地址，从而匹配 ICache 的背靠背命中能力。
    // buf_valid 在 ID 反压时暂存返回指令，discard_reg 丢弃 flush 前已发出的旧响应。
    // next PC 优先使用命中的 BTB 目标；EX 发现预测错误或 WB flush 时由 Ctrl 重定向。
    val pc_reg = RegInit(Config.START_PC)
    val va = pc_reg

    val predictor = Module(new BranchPredictor(32))
    predictor.io.lookupPc := va
    predictor.io.update   := io.bp_update
    //MMU
    io.tlb_s0_vppn     := va(31, 13)
    io.tlb_s0_va_bit12 := va(12)
    io.tlb_s0_asid     := io.mmu_config.asid.asid // 注意这里多了一级 asid
    
    val dmw0_hit = (io.mmu_config.crmd.pg === 1.U) && (io.mmu_config.crmd.da === 0.U) && (va(31, 29) === io.mmu_config.dmw0.vseg) &&
               ((io.mmu_config.crmd.plv === 0.U && io.mmu_config.dmw0.plv0 === 1.U) || (io.mmu_config.crmd.plv === 3.U && io.mmu_config.dmw0.plv3 === 1.U))
               
    val dmw1_hit = (io.mmu_config.crmd.pg === 1.U) && (io.mmu_config.crmd.da === 0.U) && (va(31, 29) === io.mmu_config.dmw1.vseg) &&
                ((io.mmu_config.crmd.plv === 0.U && io.mmu_config.dmw1.plv0 === 1.U) || (io.mmu_config.crmd.plv === 3.U && io.mmu_config.dmw1.plv3 === 1.U))
                
    val dmw_hit = dmw0_hit || dmw1_hit
    val dmw_pa  = Mux(dmw0_hit, Cat(io.mmu_config.dmw0.pseg, va(28, 0)), Cat(io.mmu_config.dmw1.pseg, va(28, 0)))
    val tlb_pa = Mux(io.tlb_s0_ps === 12.U, Cat(io.tlb_s0_ppn, va(11, 0)), Cat(io.tlb_s0_ppn(19, 9), va(20, 0)))
    
    val pa = Mux((io.mmu_config.crmd.da === 1.U) && (io.mmu_config.crmd.pg === 0.U), va,
         Mux(dmw_hit,                               dmw_pa,
         Mux(io.tlb_s0_found && io.tlb_s0_v,        tlb_pa,
         va)))

    // ------------- 新增：计算 IF 级的 MAT 并判断是否为 Uncached -------------
    val dmw_mat = Mux(dmw0_hit, io.mmu_config.dmw0.mat, io.mmu_config.dmw1.mat)
    
    val current_mat = Mux((io.mmu_config.crmd.da === 1.U) && (io.mmu_config.crmd.pg === 0.U), io.mmu_config.crmd.datf, 
                      Mux(dmw_hit, dmw_mat,                  
                      io.tlb_s0_mat))                       

    io.inst_uncached := (current_mat === 0.U)
    // ------------------------------------------------------------------------

    // ------------- 新增：IF 级 MMU 异常判定 -------------
    // 是否需要通过 TLB 进行映射翻译（开启了分页，未开启直接翻译，且没有命中直接映射窗口 DMW）
    val is_mapped = (io.mmu_config.crmd.pg === 1.U) && (io.mmu_config.crmd.da === 0.U) && !dmw_hit
    
    // 1. TLB 重填例外 (TLBR, 0x3F)：需要映射但 TLB 没找到
    val exc_tlb_refill_if = is_mapped && !io.tlb_s0_found
    // 2. 取指页无效例外 (PIF, 0x03)：找到了，但 V 位为 0
    val exc_pif = is_mapped && io.tlb_s0_found && !io.tlb_s0_v
    // 3. 页特权等级不合规例外 (PPI, 0x07)：找到了且有效，但当前特权级(PLV=3)无权访问内核页(PLV=0)
    val exc_ppi_if = is_mapped && io.tlb_s0_found && io.tlb_s0_v && (io.mmu_config.crmd.plv === 3.U) && (io.tlb_s0_plv === 0.U)

    val if_mmu_exc = exc_tlb_refill_if || exc_pif || exc_ppi_if
    // ----------------------------------------------------


    val pc_alignment_error = va(1, 0) =/= 0.U
    val req_has_exception  = pc_alignment_error || if_mmu_exc
    val req_ecode = Mux(pc_alignment_error, "h08".U(6.W),
                    Mux(exc_tlb_refill_if,  "h3F".U(6.W),
                    Mux(exc_pif,            "h03".U(6.W),
                    Mux(exc_ppi_if,         "h07".U(6.W), 0.U(6.W)))))

    // 在途请求的 PC/异常必须随地址握手锁存。背靠背取指后 pc_reg 已经指向
    // 下一条指令，不能再用 pc_reg - 4 或当前 TLB 输出反推返回数据的属性。
    val wait_data_reg       = RegInit(false.B)
    val pending_pc          = RegInit(Config.START_PC)
    val pending_has_exc     = RegInit(false.B)
    val pending_ecode       = RegInit(0.U(6.W))
    val pending_pred_taken  = RegInit(false.B)
    val pending_pred_target = RegInit(0.U(32.W))

    val discard_reg         = RegInit(false.B)
    val buf_valid           = RegInit(false.B)
    val inst_buffer         = RegInit(0.U(32.W))
    val buffer_pc           = RegInit(Config.START_PC)
    val buffer_has_exc      = RegInit(false.B)
    val buffer_ecode        = RegInit(0.U(6.W))
    val buffer_pred_taken   = RegInit(false.B)
    val buffer_pred_target  = RegInit(0.U(32.W))

    val real_data_ok        = io.inst_sram.data_ok && !discard_reg
    val data_handshaked     = wait_data_reg && real_data_ok
    val response_consumed   = data_handshaked && io.out.ready
    val buffer_consumed     = buf_valid && io.out.ready

    // 仍然最多只有一个未返回请求；当旧响应本拍能送入 ID 时，请求槽在
    // 同一拍释放，因而可与 ICache 的 data_ok/addr_ok 同拍握手下一个 PC。
    // 若 ID 反压，返回数据先进 buffer，在 buffer 被消费前不再接收新响应。
    val request_slot_available = !wait_data_reg || response_consumed
    val output_slot_available  = !buf_valid || buffer_consumed
    val allow_req = request_slot_available && output_slot_available
    val req_valid = allow_req && !io.flush

    // Do not speculate through an IF exception.  The exception instruction is
    // still fetched from safe_pa and later redirects through the precise WB path.
    val req_pred_taken  = predictor.io.predictedTaken && !req_has_exception
    val req_pred_target = predictor.io.predictedTarget

    // 取指异常仍向一个安全物理地址发请求，借用正常 data_ok 时序将异常送入流水线。
    val safe_pa = Mux(req_has_exception, "h1c000000".U(32.W), pa)

    io.inst_sram.req    := req_valid
    io.inst_sram.wr     := false.B
    io.inst_sram.size   := 2.U
    io.inst_sram.wstrb  := 0.U
    io.inst_sram.addr   := safe_pa   //////
    io.inst_sram.wdata  := 0.U

    val addr_handshaked = req_valid && io.inst_sram.addr_ok

    val set_discard = (wait_data_reg || addr_handshaked) && io.flush && !io.inst_sram.data_ok

    when(io.flush) {
        pc_reg := io.flush_target_pc
    } .elsewhen(addr_handshaked) {
        pc_reg := Mux(req_pred_taken, req_pred_target, pc_reg + 4.U)
    }

    when(io.flush) {
        wait_data_reg := false.B
    } .elsewhen(addr_handshaked) {
        wait_data_reg := true.B
    } .elsewhen(data_handshaked) {
        wait_data_reg := false.B
    }

    when(addr_handshaked) {
        pending_pc      := va
        pending_has_exc := req_has_exception
        pending_ecode   := req_ecode
        pending_pred_taken  := req_pred_taken
        pending_pred_target := req_pred_target
    }

    
    when(set_discard) {
        discard_reg := true.B
    } .elsewhen(discard_reg && io.inst_sram.data_ok) {
        discard_reg := false.B
    }

    
    when(io.flush) {
        buf_valid := false.B
    } .elsewhen(data_handshaked && !io.out.ready) {
        inst_buffer    := io.inst_sram.rdata
        buffer_pc      := pending_pc
        buffer_has_exc := pending_has_exc
        buffer_ecode   := pending_ecode
        buffer_pred_taken  := pending_pred_taken
        buffer_pred_target := pending_pred_target
        buf_valid      := true.B
    } .elsewhen(buffer_consumed) {
        buf_valid   := false.B
    }

    val final_pc      = Mux(buf_valid, buffer_pc, pending_pc)
    val final_inst    = Mux(buf_valid, inst_buffer, io.inst_sram.rdata)
    val final_has_exc = Mux(buf_valid, buffer_has_exc, pending_has_exc)
    val final_ecode   = Mux(buf_valid, buffer_ecode, pending_ecode)
    val final_pred_taken  = Mux(buf_valid, buffer_pred_taken, pending_pred_taken)
    val final_pred_target = Mux(buf_valid, buffer_pred_target, pending_pred_target)
    val final_valid   = (data_handshaked || buf_valid) && !io.flush

    // 只要发生任何 IF 级异常，一律把取回来的数据抹成 NOP 指令。
    val safe_inst = Mux(final_has_exc, "h03400000".U(32.W), final_inst)

    val out_data = WireDefault(0.U.asTypeOf(new PipelineData()))
    out_data.pc           := final_pc
    out_data.inst         := safe_inst
    out_data.predictedTaken  := final_pred_taken
    out_data.predictedTarget := final_pred_target
    out_data.hasException := final_has_exc
    out_data.ecode        := final_ecode
    
    io.out.bits  := out_data
    io.out.valid := final_valid
}
