package mycpu

import chisel3._
import chisel3.util._

class DualICacheCpuIO extends Bundle {
    val valid = Input(Bool())
    val index = Input(UInt(8.W))
    val tag = Input(UInt(20.W))
    val offset = Input(UInt(2.W))
    val uncached = Input(Bool())

    val addrOk = Output(Bool())
    val dataOk = Output(Bool())
    val line = Output(UInt(128.W))
}

/**
  * Read-only 8 KiB, two-way ICache with 64-byte lines.
  *
  * A line is four independent 128-bit sector RAMs.  The frontend sees only
  * its requested sector while refill remains a 16-beat 32-bit AXI burst.
  */
class DualICache extends Module {
    val io = IO(new Bundle {
        val cpu = new DualICacheCpuIO()
        val axi = new CacheToAxiIO()
        val invalidateAll = Input(Bool())
    })

    val sIdle :: sLookup :: sMiss :: sRefill :: Nil = Enum(4)
    val state = RegInit(sIdle)

    val tags = Seq.fill(2)(SyncReadMem(64, UInt(20.W)))
    val sectors = Seq.fill(2, 4)(SyncReadMem(64, UInt(128.W)))
    val valid = RegInit(VecInit(Seq.fill(2)(
        VecInit(Seq.fill(64)(false.B))
    )))
    val lru = RegInit(VecInit(Seq.fill(64)(false.B)))

    val reqSet = Reg(UInt(6.W))
    val reqSector = Reg(UInt(2.W))
    val reqTag = Reg(UInt(20.W))
    val reqOffset = Reg(UInt(2.W))
    val reqUncached = Reg(Bool())

    val accept = Wire(Bool())
    val tagsRead = Wire(Vec(2, UInt(20.W)))
    val sectorRead = Wire(Vec(2, Vec(4, UInt(128.W))))
    for (way <- 0 until 2) {
        tagsRead(way) := tags(way).read(
            io.cpu.index(7, 2), accept)
        for (sector <- 0 until 4) {
            sectorRead(way)(sector) := sectors(way)(sector).read(
                io.cpu.index(7, 2),
                accept && io.cpu.index(1, 0) === sector.U)
        }
    }

    val way0Hit = valid(0)(reqSet) && !reqUncached &&
        tagsRead(0) === reqTag
    val way1Hit = valid(1)(reqSet) && !reqUncached &&
        tagsRead(1) === reqTag
    val hit = way0Hit || way1Hit
    val hitWay = way1Hit
    val hitSector = Mux(hitWay,
        sectorRead(1)(reqSector), sectorRead(0)(reqSector))

    io.cpu.addrOk := state === sIdle || (state === sLookup && hit)
    accept := io.cpu.valid && io.cpu.addrOk
    when(accept) {
        reqSet := io.cpu.index(7, 2)
        reqSector := io.cpu.index(1, 0)
        reqTag := io.cpu.tag
        reqOffset := io.cpu.offset
        reqUncached := io.cpu.uncached
    }

    val victimWay = Reg(Bool())
    val refillCount = RegInit(0.U(4.W))
    val refillWords = Reg(Vec(4, UInt(32.W)))
    val suppressRefill = RegInit(false.B)

    val assembledSector = Cat(
        Mux(refillCount(1, 0) === 3.U,
            io.axi.ret_data, refillWords(3)),
        Mux(refillCount(1, 0) === 2.U,
            io.axi.ret_data, refillWords(2)),
        Mux(refillCount(1, 0) === 1.U,
            io.axi.ret_data, refillWords(1)),
        Mux(refillCount(1, 0) === 0.U,
            io.axi.ret_data, refillWords(0))
    )
    val requestedSectorDone = state === sRefill &&
        io.axi.ret_valid && (
            reqUncached ||
            (refillCount(3, 2) === reqSector &&
             refillCount(1, 0) === 3.U))

    io.cpu.dataOk := (state === sLookup && hit) ||
        requestedSectorDone
    io.cpu.line := Mux(reqUncached,
        Fill(4, io.axi.ret_data),
        Mux(requestedSectorDone, assembledSector, hitSector))

    when(io.invalidateAll) {
        for (way <- 0 until 2; set <- 0 until 64) {
            valid(way)(set) := false.B
        }
        when(state === sMiss || state === sRefill) {
            suppressRefill := true.B
        }
    }

    switch(state) {
        is(sIdle) {
            when(accept) {
                state := sLookup
            }
        }
        is(sLookup) {
            when(reqUncached || !hit) {
                victimWay := Mux(!valid(0)(reqSet), false.B,
                    Mux(!valid(1)(reqSet), true.B, lru(reqSet)))
                state := sMiss
            }.otherwise {
                lru(reqSet) := !hitWay
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
                refillWords(refillCount(1, 0)) := io.axi.ret_data

                when(!reqUncached && refillCount(1, 0) === 3.U) {
                    for (way <- 0 until 2; sector <- 0 until 4) {
                        when(victimWay === way.U &&
                             refillCount(3, 2) === sector.U) {
                            sectors(way)(sector).write(
                                reqSet, assembledSector)
                        }
                    }
                }

                when(io.axi.ret_last || reqUncached) {
                    when(!reqUncached && !suppressRefill &&
                         !io.invalidateAll) {
                        when(victimWay) {
                            tags(1).write(reqSet, reqTag)
                        }.otherwise {
                            tags(0).write(reqSet, reqTag)
                        }
                        valid(victimWay)(reqSet) := true.B
                        lru(reqSet) := !victimWay
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
    io.axi.rd_type := Mux(reqUncached, 2.U, 6.U)
    io.axi.rd_addr := Mux(reqUncached,
        Cat(reqTag, reqSet, reqSector, reqOffset, 0.U(2.W)),
        Cat(reqTag, reqSet, 0.U(6.W)))

    io.axi.wr_req := false.B
    io.axi.wr_type := 0.U
    io.axi.wr_addr := 0.U
    io.axi.wr_wstrb := 0.U
    io.axi.wr_data := 0.U
}
