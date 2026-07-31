package mycpu

import chisel3._
import chisel3.util._

class CacheToCpuIO extends Bundle {
    val valid = Input(Bool())
    val op = Input(Bool())
    val index = Input(UInt(8.W))
    val tag = Input(UInt(20.W))
    val offset = Input(UInt(4.W))
    val wstrb = Input(UInt(4.W))
    val wdata = Input(UInt(32.W))

    val addr_ok = Output(Bool())
    val data_ok = Output(Bool())
    val rdata = Output(UInt(32.W))

    val uncached = Input(Bool())
    val cacop_en = Input(Bool())
    val cacop_op = Input(UInt(2.W))
}

class CacheToAxiIO extends Bundle {
    val rd_req = Output(Bool())
    val rd_type = Output(UInt(3.W))
    val rd_addr = Output(UInt(32.W))
    val rd_rdy = Input(Bool())
    val ret_valid = Input(Bool())
    val ret_last = Input(Bool())
    val ret_data = Input(UInt(32.W))

    val wr_req = Output(Bool())
    val wr_type = Output(UInt(3.W))
    val wr_addr = Output(UInt(32.W))
    val wr_wstrb = Output(UInt(4.W))
    // A writeback is transferred one 128-bit sector at a time.  The bridge
    // emits its four 32-bit AXI beats; no 512-bit line crosses this boundary.
    val wr_data = Output(UInt(128.W))
    val wr_rdy = Input(Bool())
}

/**
  * Blocking 8 KiB, two-way DCache with 64-byte lines.
  *
  * Each line is four independent 128-bit sectors.  Address format is
  * tag[31:12] / set[11:6] / sector[5:4] / word[3:2] / byte[1:0].
  */
class Cache extends Module {
    val io = IO(new Bundle {
        val cpu = new CacheToCpuIO()
        val axi = new CacheToAxiIO()
    })

    val sIdle :: sRead :: sLookup :: sWriteback :: sMissReq :: sRefill :: Nil =
        Enum(6)
    val state = RegInit(sIdle)

    val tags = Seq.fill(2)(SyncReadMem(64, UInt(20.W)))
    val data = Seq.fill(2, 16)(SyncReadMem(64, Vec(4, UInt(8.W))))
    val valid = RegInit(VecInit(Seq.fill(2)(
        VecInit(Seq.fill(64)(false.B))
    )))
    val dirty = RegInit(VecInit(Seq.fill(2)(
        VecInit(Seq.fill(64)(false.B))
    )))
    val lru = RegInit(VecInit(Seq.fill(64)(false.B)))

    // Request payload is unreset; state defines validity.
    val reqOp = Reg(Bool())
    val reqSet = Reg(UInt(6.W))
    val reqSector = Reg(UInt(2.W))
    val reqTag = Reg(UInt(20.W))
    val reqOffset = Reg(UInt(4.W))
    val reqWstrb = Reg(UInt(4.W))
    val reqWdata = Reg(UInt(32.W))
    val reqUncached = Reg(Bool())
    val reqCacop = Reg(Bool())
    val reqCacopOp = Reg(UInt(2.W))

    val reqWord = Cat(reqSector, reqOffset(3, 2))

    // Declarations required by the single-port memory read routing below.
    val startVictimRead = WireDefault(false.B)
    val victimReadSet = WireDefault(reqSet)
    val accept = Wire(Bool())
    val startLookupRead = state === sRead
    val memoryReadSet = Mux(startVictimRead,
        victimReadSet, reqSet)
    val memoryReadEnable = startVictimRead || startLookupRead

    val tagsRead = Wire(Vec(2, UInt(20.W)))
    val dataRead = Wire(Vec(2, Vec(16, Vec(4, UInt(8.W)))))
    for (way <- 0 until 2) {
        tagsRead(way) := tags(way).read(memoryReadSet, memoryReadEnable)
        for (bank <- 0 until 16) {
            dataRead(way)(bank) := data(way)(bank).read(
                memoryReadSet,
                startVictimRead ||
                    (startLookupRead && reqWord === bank.U))
        }
    }

    val way0Hit = valid(0)(reqSet) && !reqUncached &&
        tagsRead(0) === reqTag
    val way1Hit = valid(1)(reqSet) && !reqUncached &&
        tagsRead(1) === reqTag
    val hit = way0Hit || way1Hit
    val hitWay = way1Hit
    val selectedWord = Mux(hitWay,
        dataRead(1)(reqWord), dataRead(0)(reqWord)).asUInt
    val replacementWay = Mux(!valid(0)(reqSet), false.B,
        Mux(!valid(1)(reqSet), true.B, lru(reqSet)))

    val cacopTargetWay = Mux(reqCacopOp === 2.U,
        hitWay, reqOffset(0))
    val cacopTargetValid = valid(cacopTargetWay)(reqSet)
    val cacopTargetDirty = dirty(cacopTargetWay)(reqSet)
    val cacopImmediate = state === sLookup && reqCacop && (
        reqCacopOp === 0.U ||
        (reqCacopOp === 1.U && (!cacopTargetValid || !cacopTargetDirty)) ||
        (reqCacopOp === 2.U && (!hit || !cacopTargetDirty))
    )
    val normalHitDone = state === sLookup && !reqCacop &&
        !reqUncached && hit
    // Keep request acceptance behind a registered state boundary.  The old
    // back-to-back-hit ready path made addr_ok depend on tag lookup, TLB
    // permission handling and store-buffer arbitration in the same cycle.
    // That path then drove hundreds of M1->M2 payload control pins.  The
    // backend now records acceptance explicitly, so accepting only from Idle
    // preserves the protocol while making addr_ok a short registered-state
    // decode.
    io.cpu.addr_ok := state === sIdle
    accept := io.cpu.valid && io.cpu.addr_ok

    when(accept) {
        reqOp := io.cpu.op
        reqSet := io.cpu.index(7, 2)
        reqSector := io.cpu.index(1, 0)
        reqTag := io.cpu.tag
        reqOffset := io.cpu.offset
        reqWstrb := io.cpu.wstrb
        reqWdata := io.cpu.wdata
        reqUncached := io.cpu.uncached
        reqCacop := io.cpu.cacop_en
        reqCacopOp := io.cpu.cacop_op
    }

    val missWay = WireDefault(replacementWay)
    val missTag = WireDefault(Mux(replacementWay,
        tagsRead(1), tagsRead(0)))
    val missValid = WireDefault(valid(replacementWay)(reqSet))
    val missDirty = WireDefault(dirty(replacementWay)(reqSet))
    when(reqCacop) {
        missWay := cacopTargetWay
        missTag := Mux(cacopTargetWay, tagsRead(1), tagsRead(0))
        missValid := cacopTargetValid
        missDirty := cacopTargetDirty
    }
    val normalCachedMiss = state === sLookup && !reqCacop &&
        !reqUncached && !hit
    val dirtyCacop = state === sLookup && reqCacop &&
        ((reqCacopOp === 1.U && cacopTargetValid && cacopTargetDirty) ||
         (reqCacopOp === 2.U && hit && cacopTargetDirty))
    startVictimRead := (normalCachedMiss && missValid && missDirty) ||
        dirtyCacop
    victimReadSet := reqSet

    val wbWay = Reg(Bool())
    val wbTag = Reg(UInt(20.W))
    val wbIsCacop = Reg(Bool())
    val wbUncached = Reg(Bool())
    val wbSector = RegInit(0.U(2.W))
    val wbDataValid = RegInit(false.B)
    val wbWords = Reg(Vec(16, UInt(32.W)))
    val captureVictim = RegNext(startVictimRead, false.B)
    when(captureVictim) {
        for (bank <- 0 until 16) {
            wbWords(bank) := Mux(wbWay,
                dataRead(1)(bank).asUInt, dataRead(0)(bank).asUInt)
        }
        wbDataValid := true.B
    }

    val refillCount = RegInit(0.U(4.W))
    val refillBypassMatch = state === sRefill && io.axi.ret_valid &&
        (reqUncached || refillCount === reqWord)
    val refillWord = Wire(Vec(4, UInt(8.W)))
    for (byte <- 0 until 4) {
        refillWord(byte) := Mux(
            reqOp && !reqUncached && refillCount === reqWord &&
                reqWstrb(byte),
            reqWdata(8 * byte + 7, 8 * byte),
            io.axi.ret_data(8 * byte + 7, 8 * byte))
    }

    val wbSectorData = Cat(
        wbWords(Cat(wbSector, 3.U(2.W))),
        wbWords(Cat(wbSector, 2.U(2.W))),
        wbWords(Cat(wbSector, 1.U(2.W))),
        wbWords(Cat(wbSector, 0.U(2.W))))
    val wbRequest = state === sWriteback &&
        (wbUncached || wbDataValid)
    val wbFire = wbRequest && io.axi.wr_rdy
    val wbLast = wbUncached || wbSector === 3.U

    io.cpu.data_ok := normalHitDone || cacopImmediate ||
        refillBypassMatch ||
        (wbFire && wbLast && (wbUncached || wbIsCacop))
    io.cpu.rdata := Mux(refillBypassMatch,
        io.axi.ret_data, selectedWord)

    switch(state) {
        is(sIdle) {
            when(accept) {
                state := sRead
            }
        }
        is(sRead) {
            state := sLookup
        }
        is(sLookup) {
            when(reqCacop) {
                when(dirtyCacop) {
                    wbWay := cacopTargetWay
                    wbTag := missTag
                    wbIsCacop := true.B
                    wbUncached := false.B
                    wbSector := 0.U
                    wbDataValid := false.B
                    state := sWriteback
                }.otherwise {
                    when(reqCacopOp === 0.U || reqCacopOp === 1.U) {
                        valid(cacopTargetWay)(reqSet) := false.B
                        dirty(cacopTargetWay)(reqSet) := false.B
                    }.elsewhen(reqCacopOp === 2.U && hit) {
                        valid(hitWay)(reqSet) := false.B
                        dirty(hitWay)(reqSet) := false.B
                    }
                    state := Mux(accept, sLookup, sIdle)
                }
            }.elsewhen(reqUncached) {
                when(reqOp) {
                    wbIsCacop := false.B
                    wbUncached := true.B
                    wbSector := 0.U
                    state := sWriteback
                }.otherwise {
                    state := sMissReq
                }
            }.elsewhen(hit) {
                lru(reqSet) := !hitWay
                when(reqOp) {
                    dirty(hitWay)(reqSet) := true.B
                }
                state := Mux(accept, sLookup, sIdle)
            }.otherwise {
                wbWay := replacementWay
                wbTag := missTag
                wbIsCacop := false.B
                wbUncached := false.B
                wbSector := 0.U
                wbDataValid := false.B
                state := Mux(missValid && missDirty,
                    sWriteback, sMissReq)
            }
        }
        is(sWriteback) {
            when(wbFire) {
                when(wbLast) {
                    wbDataValid := false.B
                    when(wbIsCacop) {
                        valid(wbWay)(reqSet) := false.B
                        dirty(wbWay)(reqSet) := false.B
                        state := sIdle
                    }.elsewhen(wbUncached) {
                        state := sIdle
                    }.otherwise {
                        state := sMissReq
                    }
                }.otherwise {
                    wbSector := wbSector + 1.U
                }
            }
        }
        is(sMissReq) {
            when(io.axi.rd_rdy) {
                refillCount := 0.U
                state := sRefill
            }
        }
        is(sRefill) {
            when(io.axi.ret_valid) {
                when(io.axi.ret_last) {
                    when(!reqUncached) {
                        when(wbWay) {
                            tags(1).write(reqSet, reqTag)
                        }.otherwise {
                            tags(0).write(reqSet, reqTag)
                        }
                        valid(wbWay)(reqSet) := true.B
                        dirty(wbWay)(reqSet) := reqOp
                        lru(reqSet) := !wbWay
                    }
                    state := sIdle
                }.otherwise {
                    refillCount := refillCount + 1.U
                }
            }
        }
    }

    // Store-hit update.
    for (way <- 0 until 2; bank <- 0 until 16) {
        val hitStoreWrite = state === sLookup && !reqCacop &&
            !reqUncached && hit && reqOp &&
            hitWay === way.U && reqWord === bank.U
        val refillWrite = state === sRefill && io.axi.ret_valid &&
            !reqUncached && wbWay === way.U && refillCount === bank.U
        // Keep exactly one RTL write port per bank.  Two syntactic calls to
        // SyncReadMem.write become two physical write ports after FIRRTL
        // lowering even when their guards are mutually exclusive; Vivado
        // cannot map that shape to a 7-series RAM and expands every bank into
        // LUTs and flip-flops.  NOP-Core avoids this with a single-port XPM
        // wrapper.  Muxing payload and byte enables before the sole write call
        // preserves the same behavior while remaining RAM-inference friendly.
        val storeBytes = VecInit(
            reqWdata(7, 0), reqWdata(15, 8),
            reqWdata(23, 16), reqWdata(31, 24))
        val storeMask = VecInit(
            reqWstrb(0), reqWstrb(1), reqWstrb(2), reqWstrb(3))
        val writeData = Mux(hitStoreWrite, storeBytes, refillWord)
        val writeMask = Mux(hitStoreWrite, storeMask,
            VecInit(Seq.fill(4)(true.B)))
        when(hitStoreWrite || refillWrite) {
            data(way)(bank).write(reqSet, writeData, writeMask)
        }
    }

    io.axi.rd_req := state === sMissReq
    io.axi.rd_type := Mux(reqUncached, 2.U, 6.U)
    io.axi.rd_addr := Mux(reqUncached,
        Cat(reqTag, reqSet, reqSector, reqOffset),
        Cat(reqTag, reqSet, 0.U(6.W)))

    io.axi.wr_req := wbRequest
    io.axi.wr_type := Mux(wbUncached, 2.U, 4.U)
    io.axi.wr_addr := Mux(wbUncached,
        Cat(reqTag, reqSet, reqSector, reqOffset),
        Cat(wbTag, reqSet, wbSector, 0.U(4.W)))
    io.axi.wr_wstrb := Mux(wbUncached, reqWstrb, "hf".U)
    io.axi.wr_data := Mux(wbUncached,
        Fill(4, reqWdata), wbSectorData)
}
