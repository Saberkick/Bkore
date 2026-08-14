package mycpu

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

class TlbEntry extends Bundle {
    val e       = Bool()        //Entry enable
    val ps4MB   = Bool()        //1: 2MB page, 0: 4KB page
    val vppn    = UInt(19.W)    //Virtual Page Number
    val asid    = UInt(10.W)    //Address Space ID
    val g       = Bool()        //Global flag (ANDed from lo0 and lo1)
    
    val lo0     = new TlbeloReg()
    val lo1     = new TlbeloReg()
}

class tlb extends Module {
    val io = IO(new Bundle {
        //For IF
        //Input
        val s0_vppn     = Input(UInt(19.W))
        //Distinguish left/right page
        val s0_va_bit12 = Input(Bool())
        val s0_asid     = Input(UInt(10.W))
        //Output
        //Is it founded?
        val s0_found    = Output(Bool())
        //0-31
        val s0_index    = Output(UInt(5.W))
        val s0_ppn      = Output(UInt(20.W))
        //Pagetable Size
        //If 4kb, we use 2^12 (001100)
        //If 2mb, we use 2^21 (010101)
        val s0_ps       = Output(UInt(6.W))
        val s0_plv      = Output(UInt(2.W))
        val s0_mat      = Output(UInt(2.W))
        val s0_d        = Output(Bool())
        val s0_v        = Output(Bool())

        //For EX/MEM
        val s1_vppn     = Input(UInt(19.W))
        val s1_va_bit12 = Input(Bool())
        val s1_asid     = Input(UInt(10.W))
        val s1_found    = Output(Bool())
        val s1_index    = Output(UInt(5.W))
        val s1_ppn      = Output(UInt(20.W))
        val s1_ps       = Output(UInt(6.W))
        val s1_plv      = Output(UInt(2.W))
        val s1_mat      = Output(UInt(2.W))
        val s1_d        = Output(Bool())
        val s1_v        = Output(Bool())

        // Second LSU search port.  Management operations continue to use s1;
        // ordinary lane-1 memory accesses use s2.
        val s2_vppn     = Input(UInt(19.W))
        val s2_va_bit12 = Input(Bool())
        val s2_asid     = Input(UInt(10.W))
        val s2_found    = Output(Bool())
        val s2_index    = Output(UInt(5.W))
        val s2_ppn      = Output(UInt(20.W))
        val s2_ps       = Output(UInt(6.W))
        val s2_plv      = Output(UInt(2.W))
        val s2_mat      = Output(UInt(2.W))
        val s2_d        = Output(Bool())
        val s2_v        = Output(Bool())
    
        //For INVTLB to delete some of the PTE
        val invtlb_valid = Input(Bool())
        val invtlb_op    = Input(UInt(5.W))
        //0, 1: Invalidate all TLB entries (both global and non-global)
        //4:    Invalidate non-global TLB entries where ASID matches the input
        //5:    Invalidate non-global TLB entries where both ASID and VPN match
        //6:    Invalidate TLB entries where VPN matches

        //For TLBWR / TLBFILL to write PTE
        val we      = Input(Bool())
        val w_index = Input(UInt(5.W))
        val w_dat   = Input(new TlbEntry())

        //For TLBRD to read PTE
        val r_index = Input(UInt(5.W))
        val r_dat   = Output(new TlbEntry())
    })
    // 16 项全相联 TLB，使用寄存器阵列和 16 路并行比较实现两个组合查询端口：
    // s0 服务 IF，s1 服务 EX 的 load/store、TLBSRCH/INVTLB。每项含一对奇偶页描述符，
    // 支持 4KB 与 2MB 页、ASID/global、PLV、MAT、D/V；读写管理在 WB/CSR 侧提交。
    // 这条“双端口全相联比较 -> PriorityEncoder -> 页属性选择”路径也是潜在时序热点。
    // 只有有效位需要复位。如果将整个 TlbEntry 改成 RegInit，会给约 1.5 Kbit 的
    // 页表负载字段都带上复位网络；独立的 tlb_valid 既保证上电后全部无效，
    // 又使未命中表项的其余字段不必复位。
    val tlb_table = Reg(Vec(32, new TlbEntry()))
    val tlb_valid = RegInit(VecInit(Seq.fill(32)(false.B)))
    //Write a PTE
    when(io.we) {
        tlb_table(io.w_index) := io.w_dat
        tlb_valid(io.w_index) := io.w_dat.e
    }
    //Read a PTE
    val read_entry = WireDefault(tlb_table(io.r_index))
    read_entry.e := tlb_valid(io.r_index)
    io.r_dat := Mux(tlb_valid(io.r_index), read_entry, 0.U.asTypeOf(new TlbEntry()))

    // 16 路 PriorityEncoder 后再用结果动态读取整个 Vec，会被 CIRCT/Vivado
    // 展开成较深的优先链和 16:1 多路器。下面将查询拆成 4 组，每组 4 项：
    // 先在组内保持低索引优先，再在四组之间保持低组号优先。这样在多项
    // 同时命中时仍与原实现完全一致，但关键选择深度变为两个 4 路层级。
    def selectGroup(base: Int, matches: Seq[Bool]): (Bool, UInt, TlbEntry) = {
        val match_bits = VecInit(matches).asUInt
        val local_index = PriorityEncoder(match_bits)
        val selected_entry = MuxLookup(local_index, tlb_table(base))(Seq(
            1.U(2.W) -> tlb_table(base + 1),
            2.U(2.W) -> tlb_table(base + 2),
            3.U(2.W) -> tlb_table(base + 3)
        ))
        (match_bits.orR, local_index, selected_entry)
    }

    def hierarchicalSelect(matches: Vec[Bool]): (Bool, UInt, TlbEntry) = {
        val groups = (0 until 8).map { group =>
            val base = group * 4
            selectGroup(base, (0 until 4).map(offset => matches(base + offset)))
        }

        val group_found = VecInit(groups.map(_._1))
        val group_index = PriorityEncoder(group_found.asUInt)
        val selected_local_index = MuxLookup(group_index, groups(0)._2)(
            (1 until 8).map(group => group.U(3.W) -> groups(group)._2))
        val selected_entry = MuxLookup(group_index, groups(0)._3)(
            (1 until 8).map(group => group.U(3.W) -> groups(group)._3))

        (group_found.asUInt.orR, Cat(group_index, selected_local_index), selected_entry)
    }

    //Search Port 0
    val match0 = Wire(Vec(32, Bool()))
    for (i <- 0 until 32) {
        val entry = tlb_table(i)
        val vppn_match = (io.s0_vppn(18, 9) === entry.vppn(18, 9)) && (entry.ps4MB || (io.s0_vppn(8, 0) === entry.vppn(8, 0)))
        match0(i) := tlb_valid(i) && vppn_match && (entry.asid === io.s0_asid || entry.g)
    }

    val search0 = hierarchicalSelect(match0)
    val found0 = search0._1
    val index0 = search0._2
    val hit0 = search0._3

    io.s0_found := found0
    io.s0_index := index0

    val sel0 = Mux(hit0.ps4MB, io.s0_vppn(8), io.s0_va_bit12)
    val selected_lo0 = Mux(sel0, hit0.lo1, hit0.lo0)

    // 未命中时显式输出 0，防止未复位的 payload 将 X 传到 IF 的 MAT/uncached 路径。
    io.s0_ppn := Mux(found0, selected_lo0.ppn, 0.U)
    io.s0_plv := Mux(found0, selected_lo0.plv, 0.U)
    io.s0_mat := Mux(found0, selected_lo0.mat, 0.U)
    io.s0_d   := Mux(found0, selected_lo0.d, false.B)
    io.s0_v   := Mux(found0, selected_lo0.v, false.B)
    io.s0_ps  := Mux(found0, Mux(hit0.ps4MB, 21.U(6.W), 12.U(6.W)), 0.U)

    //Search Port 1
    val match1 = Wire(Vec(32, Bool()))
    for (i <- 0 until 32) {
        val entry = tlb_table(i)
        val vppn_match = (io.s1_vppn(18, 9) === entry.vppn(18, 9)) &&  (entry.ps4MB || (io.s1_vppn(8, 0) === entry.vppn(8, 0)))
        match1(i) := tlb_valid(i) && vppn_match && (entry.asid === io.s1_asid || entry.g)
    }

    val search1 = hierarchicalSelect(match1)
    val found1 = search1._1
    val index1 = search1._2
    val hit1 = search1._3

    io.s1_found := found1
    io.s1_index := index1

    val sel1 = Mux(hit1.ps4MB, io.s1_vppn(8), io.s1_va_bit12)
    val selected_lo1 = Mux(sel1, hit1.lo1, hit1.lo0)
    
    io.s1_ppn := Mux(found1, selected_lo1.ppn, 0.U)
    io.s1_plv := Mux(found1, selected_lo1.plv, 0.U)
    io.s1_mat := Mux(found1, selected_lo1.mat, 0.U)
    io.s1_d   := Mux(found1, selected_lo1.d, false.B)
    io.s1_v   := Mux(found1, selected_lo1.v, false.B)
    io.s1_ps  := Mux(found1, Mux(hit1.ps4MB, 21.U(6.W), 12.U(6.W)), 0.U)

    // Search Port 2 (second LSU lane)
    val match2 = Wire(Vec(32, Bool()))
    for (i <- 0 until 32) {
        val entry = tlb_table(i)
        val vppn_match = (io.s2_vppn(18, 9) === entry.vppn(18, 9)) &&
            (entry.ps4MB || (io.s2_vppn(8, 0) === entry.vppn(8, 0)))
        match2(i) := tlb_valid(i) && vppn_match &&
            (entry.asid === io.s2_asid || entry.g)
    }

    val search2 = hierarchicalSelect(match2)
    val found2 = search2._1
    val index2 = search2._2
    val hit2 = search2._3
    val sel2 = Mux(hit2.ps4MB, io.s2_vppn(8), io.s2_va_bit12)
    val selected_lo2 = Mux(sel2, hit2.lo1, hit2.lo0)

    io.s2_found := found2
    io.s2_index := index2
    io.s2_ppn := Mux(found2, selected_lo2.ppn, 0.U)
    io.s2_plv := Mux(found2, selected_lo2.plv, 0.U)
    io.s2_mat := Mux(found2, selected_lo2.mat, 0.U)
    io.s2_d   := Mux(found2, selected_lo2.d, false.B)
    io.s2_v   := Mux(found2, selected_lo2.v, false.B)
    io.s2_ps  := Mux(found2, Mux(hit2.ps4MB, 21.U(6.W), 12.U(6.W)), 0.U)

    // INVTLB
    when(io.invtlb_valid) {
        for (i <- 0 until 32) {
            val entry = tlb_table(i)
            val cond1 = !entry.g
            val cond2 = entry.g
            val cond3 = (io.s1_asid === entry.asid)
            val cond4 = (io.s1_vppn(18, 9) === entry.vppn(18, 9)) && (entry.ps4MB || (io.s1_vppn(8, 0) === entry.vppn(8, 0)))

            // 先用已复位的 valid 门控整条判定，未写入表项的 payload 不参与 INVTLB。
            val should_inv = tlb_valid(i) && MuxLookup(io.invtlb_op, false.B)(Seq(
                0.U -> (cond1 || cond2),
                1.U -> (cond1 || cond2),
                2.U -> cond2,
                3.U -> cond1,
                4.U -> (cond1 && cond3),
                5.U -> (cond1 && cond3 && cond4),
                6.U -> ((cond2 || cond3) && cond4)
            ))
            when(should_inv) {
                tlb_valid(i) := false.B
            }
        }
    }
}
