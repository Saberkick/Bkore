package mycpu

import chisel3._
import chisel3.util._

/** One LSU lane presented to the banked data cache. */
class DCacheLaneIO extends Bundle {
    // Request identity is independent of addr_ok.  It selects the owning lane
    // without feeding the ready path back into Backend's valid generation.
    val request = Input(Bool())
    val valid = Input(Bool())
    val op = Input(Bool())
    val addr = Input(UInt(32.W))
    val wstrb = Input(UInt(4.W))
    val wdata = Input(UInt(32.W))
    val access_size = Input(UInt(2.W))
    val uncached = Input(Bool())
    val cacop_en = Input(Bool())
    val cacop_op = Input(UInt(2.W))
    val responseBank = Input(Bool())

    val addr_ok = Output(Bool())
    val data_ok = Output(Bool())
    val rdata = Output(UInt(32.W))
}

/**
  * 16 KiB data cache formed from two independent 8 KiB cache banks.
  *
  * Address bit 4 selects the bank.  Inside each existing Cache instance the
  * address is compressed to tag={PA[31:13],bank}, index=PA[12:5].  AXI
  * addresses are expanded back to their original bit order at the arbiter, so
  * no external interface or memory map changes.
  */
class DualBankDCache extends Module {
    val io = IO(new Bundle {
        val cpu = Vec(2, new DCacheLaneIO())
        val axi = new CacheToAxiIO()
    })

    val banks = Seq.fill(2)(Module(new Cache()))

    val laneBank = VecInit(io.cpu.map(_.addr(4)))
    for (bank <- 0 until 2) {
        val lane0Hits = io.cpu(0).request && laneBank(0) === bank.U
        val lane1Hits = io.cpu(1).request && laneBank(1) === bank.U
        val selectLane1 = !lane0Hits && lane1Hits
        // A bank with no matching request must remain idle.  Selecting lane 0
        // as the payload default is harmless, but selecting lane0.valid as the
        // default is not: a lane-0-only access to bank 1 would otherwise also
        // inject a phantom copy into bank 0.  That copy can outlive the real
        // request and corrupt a later line fill/store sequence.
        val selectedValid = (lane0Hits || lane1Hits) && Mux(selectLane1,
            io.cpu(1).valid, io.cpu(0).valid)

        banks(bank).io.cpu.valid := selectedValid
        val selectedAddr = Mux(selectLane1, io.cpu(1).addr, io.cpu(0).addr)
        banks(bank).io.cpu.op := Mux(selectLane1, io.cpu(1).op, io.cpu(0).op)
        banks(bank).io.cpu.index := selectedAddr(12, 5)
        banks(bank).io.cpu.tag := Cat(selectedAddr(31, 13), selectedAddr(4))
        banks(bank).io.cpu.offset := selectedAddr(3, 0)
        banks(bank).io.cpu.wstrb := Mux(selectLane1,
            io.cpu(1).wstrb, io.cpu(0).wstrb)
        banks(bank).io.cpu.wdata := Mux(selectLane1,
            io.cpu(1).wdata, io.cpu(0).wdata)
        banks(bank).io.cpu.access_size := Mux(selectLane1,
            io.cpu(1).access_size, io.cpu(0).access_size)
        banks(bank).io.cpu.uncached := Mux(selectLane1,
            io.cpu(1).uncached, io.cpu(0).uncached)
        banks(bank).io.cpu.cacop_en := Mux(selectLane1,
            io.cpu(1).cacop_en, io.cpu(0).cacop_en)
        banks(bank).io.cpu.cacop_op := Mux(selectLane1,
            io.cpu(1).cacop_op, io.cpu(0).cacop_op)
    }

    for (lane <- 0 until 2) {
        io.cpu(lane).addr_ok := Mux(laneBank(lane),
            banks(1).io.cpu.addr_ok, banks(0).io.cpu.addr_ok)
        io.cpu(lane).data_ok := Mux(io.cpu(lane).responseBank,
            banks(1).io.cpu.data_ok, banks(0).io.cpu.data_ok)
        io.cpu(lane).rdata := Mux(io.cpu(lane).responseBank,
            banks(1).io.cpu.rdata, banks(0).io.cpu.rdata)
    }

    // Convert Cache's compressed bank-local address back to the physical
    // address: upper[31:13], set[12:5], bank[4], byte offset[3:0].
    def expandAddress(address: UInt): UInt =
        Cat(address(31, 13), address(11, 4), address(12), address(3, 0))

    // Read ownership is held until the final return beat.  Both banks may keep
    // a miss context, but only one uses the shared AXI read path at a time.
    val readActive = RegInit(false.B)
    val readOwner = RegInit(false.B)
    val readRoundRobin = RegInit(false.B)
    val readReq = VecInit(banks.map(_.io.axi.rd_req))
    val chooseRead1 = Mux(readReq.asUInt.andR, readRoundRobin,
        !readReq(0) && readReq(1))
    val selectedReadReq = Mux(chooseRead1,
        banks(1).io.axi.rd_req, banks(0).io.axi.rd_req)
    val selectedReadType = Mux(chooseRead1,
        banks(1).io.axi.rd_type, banks(0).io.axi.rd_type)
    val selectedReadAddr = Mux(chooseRead1,
        banks(1).io.axi.rd_addr, banks(0).io.axi.rd_addr)

    io.axi.rd_req := !readActive && selectedReadReq
    io.axi.rd_type := selectedReadType
    io.axi.rd_addr := expandAddress(selectedReadAddr)
    banks(0).io.axi.rd_rdy := !readActive && !chooseRead1 && io.axi.rd_rdy
    banks(1).io.axi.rd_rdy := !readActive && chooseRead1 && io.axi.rd_rdy

    val readAccept = io.axi.rd_req && io.axi.rd_rdy
    val responseOwner = Mux(readActive, readOwner, chooseRead1)
    for (bank <- 0 until 2) {
        banks(bank).io.axi.ret_valid := io.axi.ret_valid && responseOwner === bank.U
        banks(bank).io.axi.ret_last := io.axi.ret_last && responseOwner === bank.U
        banks(bank).io.axi.ret_data := io.axi.ret_data
    }
    when(readAccept) {
        readOwner := chooseRead1
        readRoundRobin := !chooseRead1
        readActive := !(io.axi.ret_valid && io.axi.ret_last)
    }.elsewhen(readActive && io.axi.ret_valid && io.axi.ret_last) {
        readActive := false.B
    }

    // Write ownership survives until the downstream AXI B response.  Cache
    // completion is no longer the wr_rdy capture pulse: dirty eviction,
    // CACOP writeback and uncached stores all wait for wr_done.
    val writeActive = RegInit(false.B)
    val writeOwner = RegInit(false.B)
    val writeRoundRobin = RegInit(false.B)
    val writeReq = VecInit(banks.map(_.io.axi.wr_req))
    val chooseWrite1 = Mux(writeReq.asUInt.andR, writeRoundRobin,
        !writeReq(0) && writeReq(1))
    io.axi.wr_req := !writeActive && Mux(chooseWrite1,
        banks(1).io.axi.wr_req, banks(0).io.axi.wr_req)
    io.axi.wr_type := Mux(chooseWrite1,
        banks(1).io.axi.wr_type, banks(0).io.axi.wr_type)
    io.axi.wr_addr := expandAddress(Mux(chooseWrite1,
        banks(1).io.axi.wr_addr, banks(0).io.axi.wr_addr))
    io.axi.wr_wstrb := Mux(chooseWrite1,
        banks(1).io.axi.wr_wstrb, banks(0).io.axi.wr_wstrb)
    io.axi.wr_data := Mux(chooseWrite1,
        banks(1).io.axi.wr_data, banks(0).io.axi.wr_data)
    val anyWriteRequest = writeReq.asUInt.orR
    // Cache uses wr_rdy once in sMiss as a permission to enter Replace, before
    // wr_req is asserted.  When no bank has a real request both may receive
    // that permission; once wr_req appears only the selected bank is accepted.
    banks(0).io.axi.wr_rdy := !writeActive && io.axi.wr_rdy &&
        (!anyWriteRequest || !chooseWrite1)
    banks(1).io.axi.wr_rdy := !writeActive && io.axi.wr_rdy &&
        (!anyWriteRequest || chooseWrite1)
    banks(0).io.axi.wr_done := io.axi.wr_done && writeActive && !writeOwner
    banks(1).io.axi.wr_done := io.axi.wr_done && writeActive && writeOwner

    val writeAccept = io.axi.wr_req && io.axi.wr_rdy
    when(writeAccept) {
        writeActive := true.B
        writeOwner := chooseWrite1
        writeRoundRobin := !chooseWrite1
    }.elsewhen(io.axi.wr_done && writeActive) {
        writeActive := false.B
    }

    for (lane <- 0 until 2) {
        assert(!io.cpu(lane).valid || io.cpu(lane).request,
            "DCache lane valid requires a matching request identity")
    }
    for (bank <- 0 until 2) {
        val bankHasRequest = (io.cpu(0).request && laneBank(0) === bank.U) ||
            (io.cpu(1).request && laneBank(1) === bank.U)
        assert(!banks(bank).io.cpu.valid || bankHasRequest,
            "DCache bank valid requires a lane request for that bank")
    }
    assert(!(io.cpu(0).request && io.cpu(1).request && laneBank(0) === laneBank(1)),
        "same-bank LSU requests must be serialized before DualBankDCache")
    when(io.cpu(0).request && io.cpu(1).request) {
        assert(io.cpu(0).valid === io.cpu(1).valid,
            "dual-bank LSU requests must be accepted atomically")
    }
    assert(!(io.axi.wr_done && !writeActive),
        "AXI write completion arrived without a bank owner")
    assert(!(banks(0).io.axi.wr_done && banks(1).io.axi.wr_done),
        "AXI write completion must target exactly one DCache bank")
}
