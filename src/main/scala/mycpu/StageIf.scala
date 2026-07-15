package mycpu

import chisel3._
import chisel3.util._

class StageIF extends Module {
    val io = IO(new Bundle {
        //For Stage ID
        val out             = Decoupled(new PipelineData())

        val flush           = Input(Bool())
        val flush_target_pc = Input(UInt(32.W))
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
    // 再通过类 SRAM 握手访问 ICache。wait_data_reg 使前端最多只有一个未完成取指；
    // buf_valid 在 ID 反压时暂存返回指令，discard_reg 丢弃 flush 前已发出的旧响应。
    // 当前 next PC 只有 PC+4 或 Ctrl 给出的 EX/WB 重定向目标，没有预测路径。
    val pc_reg = RegInit(Config.START_PC)
    val va = pc_reg
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


    //MODDED in AXI experiment
    val wait_data_reg = RegInit(false.B)
    val discard_reg   = RegInit(false.B)
    val buf_valid     = RegInit(false.B)
    val inst_buffer   = Reg(UInt(32.W))

    // 阻塞式取指：地址一旦握手，必须等 data_ok（或 flush 后把旧响应丢弃）才能再发请求。
    val allow_req = !wait_data_reg && !buf_valid
    // 1. 删掉 !if_mmu_exc，不管有没有异常，都必须发请求去拿 data_ok
    val req_valid = allow_req && !io.flush
    val pc_alignment_error = WireDefault(false.B)
    // 2. 如果发生 MMU 异常，强行去一个合法的安全物理地址（例如复位地址）借一个data_ok
    val safe_pa = Mux(pc_alignment_error || if_mmu_exc, "h1c000000".U(32.W), pa)

    io.inst_sram.req    := req_valid
    io.inst_sram.wr     := false.B
    io.inst_sram.size   := 2.U
    io.inst_sram.wstrb  := 0.U
    io.inst_sram.addr   := safe_pa   //////
    io.inst_sram.wdata  := 0.U

    val addr_handshaked = req_valid && io.inst_sram.addr_ok

    val set_discard = (wait_data_reg || addr_handshaked) && io.flush && !io.inst_sram.data_ok
    val real_data_ok = io.inst_sram.data_ok && !discard_reg
    val data_handshaked = wait_data_reg && real_data_ok

    val next_pc = Mux(io.flush, io.flush_target_pc - 4.U, pc_reg + 4.U)

    when(io.flush) {
        pc_reg := io.flush_target_pc
    } .elsewhen(addr_handshaked) {
        pc_reg := pc_reg + 4.U
    }

    when(io.flush) {
        wait_data_reg := false.B
    } .elsewhen(addr_handshaked) {
        wait_data_reg := true.B
    } .elsewhen(data_handshaked) {
        wait_data_reg := false.B
    }

    
    when(set_discard) {
        discard_reg := true.B
    } .elsewhen(discard_reg && io.inst_sram.data_ok) {
        discard_reg := false.B
    }

    
    when(io.flush) {
        buf_valid := false.B
    } .elsewhen(real_data_ok && !io.out.ready) {
        inst_buffer := io.inst_sram.rdata
        buf_valid   := true.B
    } .elsewhen(io.out.ready) {
        buf_valid   := false.B
    }

    val current_pc  = Mux(addr_handshaked, pc_reg, pc_reg - 4.U)
    val final_inst  = Mux(buf_valid, inst_buffer, io.inst_sram.rdata)
    val final_valid = (real_data_ok || buf_valid) && !io.flush

    pc_alignment_error := (va(1, 0) =/= 0.U)
    // 4. 只要发生任何 IF 级异常，一律把取回来的数据抹成 NOP 指令
    val safe_inst = Mux(pc_alignment_error || if_mmu_exc, "h03400000".U(32.W), final_inst)

    val out_data = WireDefault(0.U.asTypeOf(new PipelineData()))
    out_data.pc           := current_pc
    out_data.inst         := safe_inst
    out_data.hasException := pc_alignment_error || if_mmu_exc
    out_data.ecode        := Mux(pc_alignment_error, "h08".U(6.W),
                             Mux(exc_tlb_refill_if,  "h3F".U(6.W),
                             Mux(exc_pif,            "h03".U(6.W),
                             Mux(exc_ppi_if,         "h07".U(6.W), 0.U(6.W)))))
    
    io.out.bits  := out_data
    io.out.valid := final_valid
}
