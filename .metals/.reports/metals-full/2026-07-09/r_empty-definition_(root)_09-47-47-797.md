error id: file://<WORKSPACE>/src/main/scala/mycpu/TLB.scala:
file://<WORKSPACE>/src/main/scala/mycpu/TLB.scala
empty definition using pc, found symbol in pc: 
empty definition using semanticdb

found definition using fallback; symbol Input
offset: 933
uri: file://<WORKSPACE>/src/main/scala/mycpu/TLB.scala
text:
```scala
package mycpu

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

class TlbEntry extends Bundle {
    //Enable
    val e     = Bool()
    //Is it a large page?
    //A large page is 2MB, since LoongArch uses a dual-page entry structure
    val ps4MB = Bool()
    //Virtual Page Number
    //32 - 12 - 1 = 19
    val vppn  = UInt(19.W)
    //Address Space ID
    //To distinguish the virtual address space of different processes
    val asid  = UInt(10.W)
    //Global
    val g     = Bool()
    
    //Physical Page Name
    val ppn0  = UInt(20.W)
    //Privilege Level
    val plv0  = UInt(2.W)
    //Memory Access Type
    //Reserved
    val mat0  = UInt(2.W)
    //Dirty
    val d0    = Bool()
    //Valid
    val v0    = Bool()
    
    val ppn1  = UInt(20.W)
    val plv1  = UInt(2.W)
    val mat1  = UInt(2.W)
    val d1    = Bool()
    val v1    = Bool()
}

class tlb extends Module {
    val = IO(I@@nput(Clock()))

    //For IF
    //Input
    val s0_vppn     = IO(Input(UInt(19.W)))
    //Distinguish left/right page
    val s0_va_bit12 = IO(Input(Bool()))
    val s0_asid     = IO(Input(UInt(10.W)))
    //Output
    //Is it founded?
    val s0_found    = IO(Output(Bool()))
    //0-15
    val s0_index    = IO(Output(UInt(4.W)))
    val s0_ppn      = IO(Output(UInt(20.W)))
    //Pagetable Size
    //If 4kb, we use 2^12 (001100)
    //If 2mb, we use 2^21 (010101)
    val s0_ps       = IO(Output(UInt(6.W)))
    val s0_plv      = IO(Output(UInt(2.W)))
    val s0_mat      = IO(Output(UInt(2.W)))
    val s0_d        = IO(Output(Bool()))
    val s0_v        = IO(Output(Bool()))

    //For MEM
    val s1_vppn     = IO(Input(UInt(19.W)))
    val s1_va_bit12 = IO(Input(Bool()))
    val s1_asid     = IO(Input(UInt(10.W)))
    val s1_found    = IO(Output(Bool()))
    val s1_index    = IO(Output(UInt(4.W)))
    val s1_ppn      = IO(Output(UInt(20.W)))
    val s1_ps       = IO(Output(UInt(6.W)))
    val s1_plv      = IO(Output(UInt(2.W)))
    val s1_mat      = IO(Output(UInt(2.W)))
    val s1_d        = IO(Output(Bool()))
    val s1_v        = IO(Output(Bool()))

    //For INVTLB to delete some of the PTE
    val invtlb_valid = IO(Input(Bool()))
    val invtlb_op    = IO(Input(UInt(5.W)))
    //0, 1: Invalidate all TLB entries (both global and non-global)
    //4:    Invalidate non-global TLB entries where ASID matches the input
    //5:    Invalidate non-global TLB entries where both ASID and VPN match
    //6:    Invalidate TLB entries where VPN matches

    //For TLBWR / TLBFILL to write PTE
    val we      = IO(Input(Bool()))
    val w_index = IO(Input(UInt(4.W)))
    val w_e     = IO(Input(Bool()))
    val w_vppn  = IO(Input(UInt(19.W)))
    val w_ps    = IO(Input(UInt(6.W)))
    val w_asid  = IO(Input(UInt(10.W)))
    val w_g     = IO(Input(Bool()))
    val w_ppn0  = IO(Input(UInt(20.W)))
    val w_plv0  = IO(Input(UInt(2.W)))
    val w_mat0  = IO(Input(UInt(2.W)))
    val w_d0    = IO(Input(Bool()))
    val w_v0    = IO(Input(Bool()))
    val w_ppn1  = IO(Input(UInt(20.W)))
    val w_plv1  = IO(Input(UInt(2.W)))
    val w_mat1  = IO(Input(UInt(2.W)))
    val w_d1    = IO(Input(Bool()))
    val w_v1    = IO(Input(Bool()))

    //For TLBRD to read PTE
    val r_index = IO(Input(UInt(4.W)))
    val r_e     = IO(Output(Bool()))
    val r_vppn  = IO(Output(UInt(19.W)))
    val r_ps    = IO(Output(UInt(6.W)))
    val r_asid  = IO(Output(UInt(10.W)))
    val r_g     = IO(Output(Bool()))
    val r_ppn0  = IO(Output(UInt(20.W)))
    val r_plv0  = IO(Output(UInt(2.W)))
    val r_mat0  = IO(Output(UInt(2.W)))
    val r_d0    = IO(Output(Bool()))
    val r_v0    = IO(Output(Bool()))
    val r_ppn1  = IO(Output(UInt(20.W)))
    val r_plv1  = IO(Output(UInt(2.W)))
    val r_mat1  = IO(Output(UInt(2.W)))
    val r_d1    = IO(Output(Bool()))
    val r_v1    = IO(Output(Bool()))

    withClock(clk) {
        val tlb_table = Reg(Vec(16, new TlbEntry()))

        //Write a PTE
        when(we) {
            val new_entry = Wire(new TlbEntry())
            new_entry.e     := w_e
            new_entry.ps4MB := (w_ps === 21.U)
            new_entry.vppn  := w_vppn
            new_entry.asid  := w_asid
            new_entry.g     := w_g
            new_entry.ppn0  := w_ppn0
            new_entry.plv0  := w_plv0
            new_entry.mat0  := w_mat0
            new_entry.d0    := w_d0
            new_entry.v0    := w_v0
            new_entry.ppn1  := w_ppn1
            new_entry.plv1  := w_plv1
            new_entry.mat1  := w_mat1
            new_entry.d1    := w_d1
            new_entry.v1    := w_v1
            
            tlb_table(w_index) := new_entry
        }

        //Read a PTE
        val read_entry = tlb_table(r_index)
        r_e    := read_entry.e
        r_ps   := Mux(read_entry.ps4MB, 21.U(6.W), 12.U(6.W))
        r_vppn := read_entry.vppn
        r_asid := read_entry.asid
        r_g    := read_entry.g
        r_ppn0 := read_entry.ppn0
        r_plv0 := read_entry.plv0
        r_mat0 := read_entry.mat0
        r_d0   := read_entry.d0
        r_v0   := read_entry.v0
        r_ppn1 := read_entry.ppn1
        r_plv1 := read_entry.plv1
        r_mat1 := read_entry.mat1
        r_d1   := read_entry.d1
        r_v1   := read_entry.v1

        //Search Port 0
        val match0 = Wire(Vec(16, Bool()))
        for (i <- 0 until 16) {
            val entry = tlb_table(i)
            val vppn_match = (s0_vppn(18, 9) === entry.vppn(18, 9)) && (entry.ps4MB || (s0_vppn(8, 0) === entry.vppn(8, 0)))
            match0(i) := entry.e && vppn_match && (entry.asid === s0_asid || entry.g)
        }

        s0_found := match0.asUInt =/= 0.U
        s0_index := PriorityEncoder(match0)
        
        val hit0 = tlb_table(s0_index)
        val sel0 = Mux(hit0.ps4MB, s0_vppn(8), s0_va_bit12)
        
        s0_ppn := Mux(sel0, hit0.ppn1, hit0.ppn0)
        s0_plv := Mux(sel0, hit0.plv1, hit0.plv0)
        s0_mat := Mux(sel0, hit0.mat1, hit0.mat0)
        s0_d   := Mux(sel0, hit0.d1, hit0.d0)
        s0_v   := Mux(sel0, hit0.v1, hit0.v0)
        s0_ps  := Mux(hit0.ps4MB, 21.U(6.W), 12.U(6.W))

        //Search Port 1
        val match1 = Wire(Vec(16, Bool()))
        for (i <- 0 until 16) {
            val entry = tlb_table(i)
            val vppn_match = (s1_vppn(18, 9) === entry.vppn(18, 9)) &&  (entry.ps4MB || (s1_vppn(8, 0) === entry.vppn(8, 0)))
            match1(i) := entry.e && vppn_match && (entry.asid === s1_asid || entry.g)
        }

        s1_found := match1.asUInt =/= 0.U
        s1_index := PriorityEncoder(match1)
        
        val hit1 = tlb_table(s1_index)
        val sel1 = Mux(hit1.ps4MB, s1_vppn(8), s1_va_bit12)
        
        s1_ppn := Mux(sel1, hit1.ppn1, hit1.ppn0)
        s1_plv := Mux(sel1, hit1.plv1, hit1.plv0)
        s1_mat := Mux(sel1, hit1.mat1, hit1.mat0)
        s1_d   := Mux(sel1, hit1.d1, hit1.d0)
        s1_v   := Mux(sel1, hit1.v1, hit1.v0)
        s1_ps  := Mux(hit1.ps4MB, 21.U(6.W), 12.U(6.W))

        //INVTLB
        when(invtlb_valid) {
            for (i <- 0 until 16) {
                val entry = tlb_table(i)
                val cond1 = !entry.g
                val cond2 = entry.g
                val cond3 = (s1_asid === entry.asid)
                //PTE
                val cond4 = (s1_vppn(18, 9) === entry.vppn(18, 9)) && (entry.ps4MB || (s1_vppn(8, 0) === entry.vppn(8, 0)))

                val should_inv = MuxLookup(invtlb_op, false.B)(Seq(
                    0.U -> (cond1 || cond2),
                    1.U -> (cond1 || cond2),
                    4.U -> (cond1 && cond3),
                    5.U -> (cond1 && cond3 && cond4),
                    6.U -> ((cond2 || cond3) && cond4)
                ))
                //We just change its e to 0
                when(should_inv) {
                    val updated_entry = WireDefault(entry)
                    updated_entry.e := false.B
                    tlb_table(i) := updated_entry
                }
            }
        }
    }
}

object TlbGen extends App {
    ChiselStage.emitSystemVerilogFile(
        new tlb(),
        firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
    )
}
```


#### Short summary: 

empty definition using pc, found symbol in pc: 