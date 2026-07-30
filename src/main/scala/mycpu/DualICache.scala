package mycpu

import chisel3._
import chisel3.util._

class DualICacheCpuIO extends Bundle {
    val valid    = Input(Bool())
    val index    = Input(UInt(8.W))
    val tag      = Input(UInt(20.W))
    val offset   = Input(UInt(2.W))
    val uncached = Input(Bool())

    val addrOk = Output(Bool())
    val dataOk = Output(Bool())
    val line   = Output(UInt(128.W))
}

/**
  * Read-only 8 KiB, two-way, 16-byte-line instruction cache.
  *
  * A hit returns the whole line so the frontend can select two adjacent
  * instructions.  The external refill path remains the competition-standard
  * 32-bit AXI burst interface.
  */
class DualICache extends Module {
    val io = IO(new Bundle {
        val cpu = new DualICacheCpuIO()
        val axi = new CacheToAxiIO()
        val invalidateAll = Input(Bool())
    })

    val sIdle :: sLookup :: sMiss :: sRefill :: Nil = Enum(4)
    val state = RegInit(sIdle)

    val tag0 = SyncReadMem(256, UInt(20.W))
    val tag1 = SyncReadMem(256, UInt(20.W))
    val data0 = SyncReadMem(256, UInt(128.W))
    val data1 = SyncReadMem(256, UInt(128.W))
    val valid0 = RegInit(VecInit(Seq.fill(256)(false.B)))
    val valid1 = RegInit(VecInit(Seq.fill(256)(false.B)))
    val lru = RegInit(VecInit(Seq.fill(256)(false.B)))

    val reqIndex = RegInit(0.U(8.W))
    val reqTag = RegInit(0.U(20.W))
    val reqOffset = RegInit(0.U(2.W))
    val reqUncached = RegInit(false.B)
    val victimWay = RegInit(false.B)
    val refillCount = RegInit(0.U(2.W))
    val refillWords = Reg(Vec(4, UInt(32.W)))
    val suppressRefill = RegInit(false.B)

    val canAccept = state === sIdle || (state === sLookup && !reqUncached)
    val readEnable = io.cpu.valid && canAccept
    val tagRead0 = tag0.read(io.cpu.index, readEnable)
    val tagRead1 = tag1.read(io.cpu.index, readEnable)
    val dataRead0 = data0.read(io.cpu.index, readEnable)
    val dataRead1 = data1.read(io.cpu.index, readEnable)

    val way0Hit = valid0(reqIndex) && tagRead0 === reqTag && !reqUncached
    val way1Hit = valid1(reqIndex) && tagRead1 === reqTag && !reqUncached
    val hit = way0Hit || way1Hit
    val hitLine = Mux(way1Hit, dataRead1, dataRead0)

    // Lookup can accept the next request only when the current request hits.
    io.cpu.addrOk := state === sIdle || (state === sLookup && hit)
    val accept = io.cpu.valid && io.cpu.addrOk

    val assembledLine = Cat(
        Mux(refillCount === 3.U, io.axi.ret_data, refillWords(3)),
        Mux(refillCount === 2.U, io.axi.ret_data, refillWords(2)),
        Mux(refillCount === 1.U, io.axi.ret_data, refillWords(1)),
        Mux(refillCount === 0.U, io.axi.ret_data, refillWords(0))
    )
    val refillDone = state === sRefill && io.axi.ret_valid &&
        (io.axi.ret_last || reqUncached)

    io.cpu.dataOk := (state === sLookup && hit) || refillDone
    io.cpu.line := Mux(reqUncached, Fill(4, io.axi.ret_data),
        Mux(refillDone, assembledLine, hitLine))

    when(io.invalidateAll) {
        for (idx <- 0 until 256) {
            valid0(idx) := false.B
            valid1(idx) := false.B
        }
        when(state === sMiss || state === sRefill) {
            suppressRefill := true.B
        }
    }

    when(accept) {
        reqIndex := io.cpu.index
        reqTag := io.cpu.tag
        reqOffset := io.cpu.offset
        reqUncached := io.cpu.uncached
    }

    switch(state) {
        is(sIdle) {
            when(accept) {
                state := sLookup
            }
        }
        is(sLookup) {
            when(reqUncached || !hit) {
                val replacement = Mux(!valid0(reqIndex), false.B,
                    Mux(!valid1(reqIndex), true.B, lru(reqIndex)))
                victimWay := replacement
                state := sMiss
            }.otherwise {
                lru(reqIndex) := way0Hit
                state := Mux(accept, sLookup, sIdle)
            }
        }
        is(sMiss) {
            when(io.axi.rd_rdy) {
                refillCount := 0.U
                state := sRefill
            }
        }
        is(sRefill) {
            when(io.axi.ret_valid) {
                refillWords(refillCount) := io.axi.ret_data
                when(io.axi.ret_last || reqUncached) {
                    when(!reqUncached && !suppressRefill && !io.invalidateAll) {
                        when(victimWay) {
                            tag1.write(reqIndex, reqTag)
                            data1.write(reqIndex, assembledLine)
                            valid1(reqIndex) := true.B
                        }.otherwise {
                            tag0.write(reqIndex, reqTag)
                            data0.write(reqIndex, assembledLine)
                            valid0(reqIndex) := true.B
                        }
                        lru(reqIndex) := !victimWay
                    }
                    suppressRefill := false.B
                    state := sIdle
                }.otherwise {
                    refillCount := refillCount + 1.U
                }
            }
        }
    }

    io.axi.rd_req := state === sMiss
    io.axi.rd_type := Mux(reqUncached, 2.U, 4.U)
    io.axi.rd_addr := Cat(reqTag, reqIndex,
        Mux(reqUncached, Cat(reqOffset, 0.U(2.W)), 0.U(4.W)))

    io.axi.wr_req := false.B
    io.axi.wr_type := 0.U
    io.axi.wr_addr := 0.U
    io.axi.wr_wstrb := 0.U
    io.axi.wr_data := 0.U
}
