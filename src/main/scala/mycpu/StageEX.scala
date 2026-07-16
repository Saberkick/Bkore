package mycpu

import chisel3._
import chisel3.util._

class StageEX extends Module {
    val io = IO(new Bundle {
        val in  = Flipped(Decoupled(new PipelineData()))
        val out = Decoupled(new PipelineData())

        val fwdOut = Output(new ForwardingData())

        val branch_req = Output(Bool())
        val branch_pc  = Output(UInt(32.W))
        val bp_update  = Output(new BranchPredictorUpdate())

        val flush = Input(Bool())

        val timer_in   = Input(UInt(64.W))
        val data_sram  = new SramIo()
        val data_uncached = Output(Bool())

        val mem_has_exc_in = Input(Bool())


        val mmu_config   = Input(new MmuConfig())
        val tlb_s1_vppn     = Output(UInt(19.W))
        val tlb_s1_va_bit12 = Output(Bool())
        val tlb_s1_asid     = Output(UInt(10.W))
        val tlb_s1_found    = Input(Bool())
        val tlb_s1_index    = Input(UInt(4.W))  // 新增：极其重要！用于 tlbsrch 指令获取命中索引
        val tlb_s1_ppn      = Input(UInt(20.W))
        val tlb_s1_ps       = Input(UInt(6.W))
        val tlb_s1_plv      = Input(UInt(2.W))  // 新增：用于 Load/Store 页特权不合规(PPI)判定
        val tlb_s1_mat      = Input(UInt(2.W))  // 新增：存储类型
        val tlb_s1_d        = Input(Bool())    // 新增：极其重要！用于 Store 指令判定页修改例外(PME)
        val tlb_s1_v        = Input(Bool())    // 用于判定数据页无效(PIL/PIS)

        val invtlb_valid = Output(Bool())
        val invtlb_op    = Output(UInt(5.W))
    })

    



    // EX 是主要组合逻辑级：选择 MEM/WB 前递、执行 ALU/乘除法、解析分支，
    // 同时生成访存虚拟地址并完成 DMW/TLB 翻译和地址/页异常检查。
    // 访存请求在此只等待 addr_ok；数据返回与 load 扩展由 MEM 负责。
    val valid_reg = RegInit(false.B)
    val data_reg  = RegInit(0.U.asTypeOf(new PipelineData()))

    //MDU FSM
    val is_div = (data_reg.mduOp === MduOp.DIV_W || data_reg.mduOp === MduOp.MOD_W || 
                  data_reg.mduOp === MduOp.DIV_WU || data_reg.mduOp === MduOp.MOD_WU) && !data_reg.hasException
    
    val is_mul = (data_reg.mduOp === MduOp.MUL_W || data_reg.mduOp === MduOp.MULH_W || 
                  data_reg.mduOp === MduOp.MULH_WU) && !data_reg.hasException
    
    val is_mdu = data_reg.resFromMulDiv && !data_reg.hasException

    val mdu_busy = RegInit(false.B)
    val mdu_finished = RegInit(false.B)
    val div_done = WireDefault(false.B)
    val mul_start = WireDefault(false.B)
    val div_start = WireDefault(false.B)
    val mdu_start = mul_start || div_start
    val mul_done = RegNext(mul_start, false.B)

    val mdu_ready = !is_mdu || mdu_finished || (mdu_busy && (div_done || mul_done))
    val ready_go = Wire(Bool()) //MODDED in AXI experiment
    val allow_in = !valid_reg || (ready_go && io.out.ready)

    when(io.flush) {
        mdu_busy := false.B
        mdu_finished := false.B
    } .elsewhen(mdu_start) {
        mdu_busy := true.B
    } .elsewhen(mdu_busy && (div_done || mul_done)) {
        mdu_busy := false.B
        mdu_finished := !io.out.ready 
    } .elsewhen(valid_reg && ready_go && io.out.ready) {
        mdu_finished := false.B 
    }
    

    //Pipeline Settings
    io.in.ready := allow_in
    when(io.flush) {
        valid_reg := false.B
    } .elsewhen(allow_in) {
        valid_reg := io.in.valid
    }
    when(io.in.valid && allow_in) { data_reg := io.in.bits }
    

    // 前递已在 ID -> EX 边界完成。EX 内只使用本级寄存器中的稳定操作数，
    // 避免 MEM.destReg 的比较结果继续穿过 ALU、TLB 和 Cache 请求门控。
    val src1_fwd = data_reg.src1_value
    val src2_fwd = data_reg.src2_value

    // 分支在 EX 解析。只有方向或目标预测错误才通知 Ctrl 重定向并 flush IF/ID；
    // 预测正确的 taken 分支不再付出冲刷代价。
    val eq  = (src1_fwd === src2_fwd)
    val lt  = (src1_fwd.asSInt < src2_fwd.asSInt)
    val ltu = (src1_fwd < src2_fwd)

    val branch_taken = MuxLookup(data_reg.brType, false.B)(Seq(
        BrType.BEQ  -> eq,      BrType.BNE  -> !eq,     BrType.BLT  -> lt,
        BrType.BGE  -> !lt,     BrType.BLTU -> ltu,     BrType.BGEU -> !ltu,
        BrType.JIRL -> true.B,  BrType.B   -> true.B,   BrType.BL  -> true.B
    ))
    val is_branch = data_reg.brType =/= BrType.NOP
    val br_base = Mux(data_reg.brType === BrType.JIRL, src1_fwd, data_reg.pc)
    val branch_target = br_base + data_reg.imm
    val actual_taken = is_branch && branch_taken
    val resolved_next_pc = Mux(actual_taken, branch_target, data_reg.pc + 4.U)

    // Branches are single-cycle EX operations, but use the common fire condition
    // so a stalled MEM stage cannot cause repeated redirects or predictor updates.
    val resolve_fire = valid_reg && ready_go && io.out.ready && !io.flush &&
                       !data_reg.hasException && !io.mem_has_exc_in
    val prediction_wrong = (data_reg.predictedTaken =/= actual_taken) ||
                           (actual_taken && data_reg.predictedTarget =/= branch_target)
    val prediction_check = is_branch || data_reg.predictedTaken

    io.branch_req := resolve_fire && prediction_check && prediction_wrong
    io.branch_pc  := resolved_next_pc

    io.bp_update.valid    := resolve_fire && prediction_check
    io.bp_update.pc       := data_reg.pc
    io.bp_update.isBranch := is_branch
    io.bp_update.taken    := actual_taken
    io.bp_update.target   := branch_target

    //ALU
    val alu_src1 = Mux(data_reg.src1IsPC, data_reg.pc, src1_fwd)
    val alu_src2 = Mux(data_reg.src2IsImm, data_reg.imm, Mux(data_reg.src2IsFour, 4.U, src2_fwd))

    val alu = Module(new ALU())
    alu.io.aluOp := data_reg.aluOp
    alu.io.src1  := alu_src1
    alu.io.src2  := alu_src2
    val alu_res  = alu.io.res

    // load/store/CACOP 的地址形式固定为 rj + sign_extend(imm12)。单独的地址
    // 加法器使访存关键路径绕过通用 ALU 的操作选择和 12 路结果多路器。
    val is_mem_addr_op = data_reg.resFromMem || data_reg.memWe || data_reg.is_cacop
    val mem_va = src1_fwd + data_reg.imm

    //MMU
    val va = mem_va

    val is_tlbsrch = data_reg.tlbOp === TlbOp.SRCH
    val is_invtlb  = data_reg.tlbOp === TlbOp.INV

    io.tlb_s1_vppn := Mux(is_invtlb,  src2_fwd(31, 13), 
                      Mux(is_tlbsrch, io.mmu_config.tlbehi.vppn, // 增加了 .tlbehi
                                      va(31, 13)))
    io.tlb_s1_va_bit12 := va(12)
    io.tlb_s1_asid := Mux(is_invtlb,  src1_fwd(9, 0), 
                                      io.mmu_config.asid.asid) // 增加了 .asid

    io.invtlb_valid := is_invtlb && valid_reg && !data_reg.hasException
    io.invtlb_op    := data_reg.invtlb_op

    val tlbsrch_res = Cat(!io.tlb_s1_found, 0.U(27.W), io.tlb_s1_index)
    val tlbsrch_mask = Mux(io.tlb_s1_found, "h8000000F".U(32.W), "h80000000".U(32.W))

    val dmw0_hit = (io.mmu_config.crmd.pg === 1.U) && (io.mmu_config.crmd.da === 0.U) && (va(31, 29) === io.mmu_config.dmw0.vseg) &&
               ((io.mmu_config.crmd.plv === 0.U && io.mmu_config.dmw0.plv0 === 1.U) || (io.mmu_config.crmd.plv === 3.U && io.mmu_config.dmw0.plv3 === 1.U))
    val dmw1_hit = (io.mmu_config.crmd.pg === 1.U) && (io.mmu_config.crmd.da === 0.U) && (va(31, 29) === io.mmu_config.dmw1.vseg) &&
                ((io.mmu_config.crmd.plv === 0.U && io.mmu_config.dmw1.plv0 === 1.U) || (io.mmu_config.crmd.plv === 3.U && io.mmu_config.dmw1.plv3 === 1.U))
    val dmw_hit = dmw0_hit || dmw1_hit
    val dmw_pa  = Mux(dmw0_hit, Cat(io.mmu_config.dmw0.pseg, va(28, 0)), Cat(io.mmu_config.dmw1.pseg, va(28, 0)))
    val tlb_pa = Mux(io.tlb_s1_ps === 12.U, Cat(io.tlb_s1_ppn, va(11, 0)), Cat(io.tlb_s1_ppn(19, 9), va(20, 0)))

    val pa = Mux((io.mmu_config.crmd.da === 1.U) && (io.mmu_config.crmd.pg === 0.U), va,
         Mux(dmw_hit,                               dmw_pa,
         Mux(io.tlb_s1_found && io.tlb_s1_v,        tlb_pa,
         va)))
    val cacop_is_hit_inval = data_reg.is_cacop && (data_reg.cacop_op(4, 3) === "b10".U)
    val cacop_is_index     = data_reg.is_cacop && (data_reg.cacop_op(4, 3) =/= "b10".U)
    

    // ------------- 新增：计算 EX 级的 MAT 并判断是否为 Uncached -------------
    val dmw_mat = Mux(dmw0_hit, io.mmu_config.dmw0.mat, io.mmu_config.dmw1.mat)
    
    val current_mat = Mux((io.mmu_config.crmd.da === 1.U) && (io.mmu_config.crmd.pg === 0.U), io.mmu_config.crmd.datm, // 访存用 datm
                      Mux(dmw_hit, dmw_mat,                  
                      io.tlb_s1_mat))

    io.data_uncached := (current_mat === 0.U) // MAT 为 0 即为 Uncached
    // ------------------------------------------------------------------------

    //MDU
    //MODDED
    val mdu_src1_reg = Reg(UInt(32.W))
    val mdu_src2_reg = Reg(UInt(32.W))

    when(mdu_start) {
        mdu_src1_reg := src1_fwd
        mdu_src2_reg := src2_fwd
    }

    val real_mdu_src1 = Mux(mdu_busy || mdu_finished, mdu_src1_reg, src1_fwd)
    val real_mdu_src2 = Mux(mdu_busy || mdu_finished, mdu_src2_reg, src2_fwd)

    val is_signed_mdu = data_reg.mduOp === MduOp.MULH_W || data_reg.mduOp === MduOp.DIV_W || data_reg.mduOp === MduOp.MOD_W
    val mul = Module(new Multiplier())
    mul.io.src1     := real_mdu_src1
    mul.io.src2     := real_mdu_src2
    mul.io.isSigned := is_signed_mdu
    mul_start       := valid_reg && is_mul && !mdu_busy && !mdu_finished && !io.flush
    
    val div = Module(new Divider())
    val div_src1_abs = Mux(is_signed_mdu && real_mdu_src1(31), (~real_mdu_src1 + 1.U), real_mdu_src1)
    val div_src2_abs = Mux(is_signed_mdu && real_mdu_src2(31), (~real_mdu_src2 + 1.U), real_mdu_src2)
    
    val div_request = valid_reg && is_div && !mdu_busy && !mdu_finished && !io.flush
    div.io.enable := div_request
    div.io.flush  := io.flush
    div.io.a      := div_src1_abs
    div.io.b      := div_src2_abs
    div_start     := div_request && div.io.ready
    div_done      := div.io.done 
    
    val q_sign = real_mdu_src1(31) ^ real_mdu_src2(31)
    val r_sign = real_mdu_src1(31)
    val signed_q = Mux(is_signed_mdu && q_sign, (~div.io.q + 1.U), div.io.q)
    val signed_r = Mux(is_signed_mdu && r_sign, (~div.io.r + 1.U), div.io.r)
    // 保留原行为级模型的除零约定，并确保负被除数不会在符号后处理时把商改成 1。
    val divide_by_zero = real_mdu_src2 === 0.U
    val final_q = Mux(divide_by_zero, "hffffffff".U, signed_q)
    val final_r = Mux(divide_by_zero, real_mdu_src1, signed_r)

    val mdu_res = MuxLookup(data_reg.mduOp, 0.U(32.W))(Seq(
        MduOp.MUL_W   -> mul.io.result64(31, 0),
        MduOp.MULH_W  -> mul.io.result64(63, 32),
        MduOp.MULH_WU -> mul.io.result64(63, 32),
        MduOp.DIV_W   -> final_q,
        MduOp.MOD_W   -> final_r,
        MduOp.DIV_WU  -> div.io.q,
        MduOp.MOD_WU  -> div.io.r
    ))

    //
    val csr_mask = Mux(data_reg.src1_addr === 0.U, 0.U(32.W),
                   Mux(data_reg.src1_addr === 1.U, "hFFFFFFFF".U(32.W),
                   src1_fwd))
    // Report no optional cache capability for now.  The NSCSCC startup code
    // will consequently skip CACOP-based I/D/L2 cache initialization.
    val cpucfg_result = 0.U(32.W)
    val base_ex_result = Mux(is_mem_addr_op, mem_va, alu_res)
    val final_ex_result = Mux(data_reg.isCpucfg, cpucfg_result,
                          Mux(is_tlbsrch, tlbsrch_res,
                          Mux(data_reg.rdtimel, io.timer_in(31, 0),
                          Mux(data_reg.rdtimeh, io.timer_in(63, 32),
                          Mux(data_reg.isCsr, src2_fwd, 
                          Mux(data_reg.resFromMulDiv, mdu_res, base_ex_result))))))

    // 强制 tlbsrch 只能修改 TLBIDX 的第 31 位(NE) 和低 4 位(Index)
    val aux_data = Mux(is_tlbsrch, tlbsrch_mask, 
                   Mux(data_reg.isCsr, csr_mask, 
                   src2_fwd))

    //DataMEM
    //Memory Check
    val isWord = data_reg.lsOp === LsOp.LD_W || data_reg.lsOp === LsOp.ST_W
    val isHalf = data_reg.lsOp === LsOp.LD_H || data_reg.lsOp === LsOp.LD_HU || data_reg.lsOp === LsOp.ST_H
    val ale = (data_reg.resFromMem || data_reg.memWe) && valid_reg && 
              ((isWord && (mem_va(1, 0) =/= 0.U)) || (isHalf && mem_va(0) === 1.U))
    // ------------- 新增：EX 级 MMU 访存异常判定 -------------
    val is_mapped = (io.mmu_config.crmd.pg === 1.U) && (io.mmu_config.crmd.da === 0.U) && !dmw_hit
    val is_load   = data_reg.resFromMem && valid_reg && !data_reg.hasException && !ale // 前方无错且是Load
    val is_store  = data_reg.memWe      && valid_reg && !data_reg.hasException && !ale // 前方无错且是Store

    val valid_hit_inval    = cacop_is_hit_inval && valid_reg && !data_reg.hasException && !ale

    // ★ 修改：命中作废型 CACOP 也要参与 TLBR 和 PPI 判定
    val is_ls     = is_load || is_store || valid_hit_inval

    // 1. TLB 重填例外 (TLBR, 0x3F)
    val exc_tlb_refill_ex = is_ls && is_mapped && !io.tlb_s1_found
    // 2. 页特权等级不合规 (PPI, 0x07)
    val exc_ppi_ex = is_ls && is_mapped && io.tlb_s1_found && io.tlb_s1_v && (io.mmu_config.crmd.plv === 3.U) && (io.tlb_s1_plv === 0.U)
    // 3. Load / Store 页无效 (PIL, 0x01 / PIS, 0x02)
    // ★ 修改：命中作废型 CACOP 视为读操作，触发 PIL
    val exc_pil = (is_load || valid_hit_inval) && is_mapped && io.tlb_s1_found && !io.tlb_s1_v
    val exc_pis = is_store && is_mapped && io.tlb_s1_found && !io.tlb_s1_v
    // 4. 页修改例外 (PME, 0x04)：写操作，页有效且特权级合规，但 D 位(脏位)为 0
    val exc_pme = is_store && is_mapped && io.tlb_s1_found && io.tlb_s1_v && !(exc_ppi_ex) && !io.tlb_s1_d

    // Cache 请求只依赖这个浅层 fault 汇总，不经过异常码编码网络。
    val mmu_fault = exc_tlb_refill_ex || exc_ppi_ex || exc_pil || exc_pis || exc_pme
    // --------------------------------------------------------
    //MODDED in AXI experiment
    val is_mem_inst = (data_reg.resFromMem || data_reg.memWe || data_reg.is_cacop) && valid_reg && !data_reg.hasException && !ale && !io.mem_has_exc_in
    val is_mem = is_mem_inst && !mmu_fault
    // 每条访存指令只允许一次 addr_ok 握手。若 MEM/Cache 反压导致 EX 暂留，
    // mem_req_sent 会抑制重复请求，直到该指令真正离开 EX 或被 flush。
    val mem_req_sent = RegInit(false.B)
    val req_fire = io.data_sram.req && io.data_sram.addr_ok

    when(io.flush || (valid_reg && ready_go && io.out.ready)) {
        mem_req_sent := false.B
    } .elsewhen(req_fire) {
        mem_req_sent := true.B
    }
    //Read/Write DataMEM
    val stMaskB = "b0001".U(4.W) << mem_va(1, 0)
    val stMaskH = Mux(mem_va(1), "b1100".U(4.W), "b0011".U(4.W))
    val stMaskW = "b1111".U(4.W)

    io.data_sram.req   := is_mem && !mem_req_sent && io.out.ready && !io.flush
    io.data_sram.wr    := Mux(valid_reg, data_reg.memWe, false.B)
    io.data_sram.size  := Mux(isWord, 2.U, Mux(isHalf, 1.U, 0.U))
    io.data_sram.wstrb := Mux(data_reg.memWe && is_mem && !io.flush, Mux(isWord, stMaskW, Mux(isHalf, stMaskH, stMaskB)), 0.U(4.W))
    val final_pa_high = Mux(cacop_is_index, va(31,12), pa(31,12))
    val final_pa_true = Cat(final_pa_high, va(11, 0))
    io.data_sram.addr := Mux(valid_reg, final_pa_true, 0.U)

    val wdata_b = Fill(4, src2_fwd(7, 0))
    val wdata_h = Fill(2, src2_fwd(15, 0))
    io.data_sram.wdata := Mux(isWord, src2_fwd, Mux(isHalf, wdata_h, wdata_b))
    //MODDED in AXI experiment
    ready_go := mdu_ready && (!is_mem_inst || io.data_sram.addr_ok || mem_req_sent)

    //
    val out_data = WireDefault(data_reg) 
    out_data.ex_result := final_ex_result
    out_data.aux_data  := aux_data
    val has_new_exc = ale || mmu_fault
    out_data.hasException := data_reg.hasException || has_new_exc
    // 新产生的异常条件两两互斥：ALE 会禁止 is_load/is_store，TLBR 与
    // found 类异常互斥，V/PLV/D 条件也已依次排除。按位与/或编码比多层
    // 优先 Mux 更浅；前级异常仍保持最高优先级。
    val generated_ecode =
        (Fill(6, ale)               & "h09".U(6.W)) |
        (Fill(6, exc_tlb_refill_ex) & "h3F".U(6.W)) |
        (Fill(6, exc_pil)           & "h01".U(6.W)) |
        (Fill(6, exc_pis)           & "h02".U(6.W)) |
        (Fill(6, exc_ppi_ex)        & "h07".U(6.W)) |
        (Fill(6, exc_pme)           & "h04".U(6.W))
    out_data.ecode := Mux(data_reg.hasException, data_reg.ecode, generated_ecode)

    //MODDED
    val squash_mem = io.mem_has_exc_in
    out_data.memWe      := data_reg.memWe && !squash_mem
    out_data.resFromMem := data_reg.resFromMem && !squash_mem

    out_data.is_cacop  := Mux(valid_reg, data_reg.is_cacop, false.B) 
    out_data.cacop_op  := Mux(valid_reg, data_reg.cacop_op, 0.U(5.W))

    io.out.valid := valid_reg && ready_go && !io.flush
    io.out.bits  := out_data

    io.fwdOut.valid        := valid_reg
    io.fwdOut.regWriteEn   := data_reg.regWriteEn
    io.fwdOut.regWriteAddr := data_reg.destReg
    io.fwdOut.resFromMem   := data_reg.resFromMem
    // 只输出能在 EX 真正产生的 GPR 结果。load/CSR 仍由 ID 冒险逻辑等待，
    // 因而不把 TLB/异常网络重新接回 ID -> EX 旁路路径。
    io.fwdOut.result       := Mux(data_reg.isCpucfg, cpucfg_result,
                              Mux(data_reg.rdtimel, io.timer_in(31, 0),
                              Mux(data_reg.rdtimeh, io.timer_in(63, 32),
                              Mux(data_reg.resFromMulDiv, mdu_res, alu_res))))
    io.fwdOut.isCsr        := data_reg.isCsr
}
