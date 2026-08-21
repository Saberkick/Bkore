package mycpu

import chisel3._
import chisel3.util._

/** Iterative implementation of the 2025 competition RRIWINZ instruction. */
class RrwinzUnit extends Module {
    val io = IO(new Bundle {
        val enable = Input(Bool())
        val flush = Input(Bool())
        val consume = Input(Bool())
        val oldRd = Input(UInt(32.W))
        val rj = Input(UInt(32.W))
        val imm = Input(UInt(16.W))
        val done = Output(Bool())
        val result = Output(UInt(32.W))
    })

    val sIdle :: sCount :: sReduce :: sRotate :: sDone :: Nil = Enum(5)
    val state = RegInit(sIdle)
    val rjReg = RegInit(0.U(32.W))
    val oldRdReg = RegInit(0.U(32.W))
    val resultBits = RegInit(VecInit(Seq.fill(32)(false.B)))
    val rjBaseReg = RegInit(0.U(5.W))
    val rdBaseReg = RegInit(0.U(5.W))
    val rjWidthReg = RegInit(0.U(6.W))
    val rdWidthReg = RegInit(0.U(6.W))
    val indexReg = RegInit(0.U(6.W))
    val countReg = RegInit(0.U(6.W))

    // I16[4:0]=rj_base, I16[9:5]=offset, I16[14:10]=rd_base.
    // The top unused I16 bit is intentionally ignored.
    val requestedWidth = Cat(0.U(1.W), io.imm(9, 5))
    val rjRemaining = 32.U(6.W) - Cat(0.U(1.W), io.imm(4, 0))
    val rdRemaining = 32.U(6.W) - Cat(0.U(1.W), io.imm(14, 10))
    val inputRjWidth = Mux(requestedWidth > rjRemaining,
        rjRemaining, requestedWidth)
    val inputRdWidth = Mux(requestedWidth > rdRemaining,
        rdRemaining, requestedWidth)

    io.done := state === sDone
    io.result := resultBits.asUInt

    when(io.flush) {
        state := sIdle
    }.otherwise {
        switch(state) {
            is(sIdle) {
                when(io.enable) {
                    rjReg := io.rj
                    oldRdReg := io.oldRd
                    resultBits := VecInit(io.oldRd.asBools)
                    rjBaseReg := io.imm(4, 0)
                    rdBaseReg := io.imm(14, 10)
                    rjWidthReg := inputRjWidth
                    rdWidthReg := inputRdWidth
                    indexReg := 0.U
                    countReg := 0.U
                    state := Mux(inputRdWidth === 0.U, sDone,
                        Mux(inputRjWidth === 0.U, sReduce, sCount))
                }
            }
            is(sCount) {
                val sourceIndex = Cat(0.U(1.W), rjBaseReg) + indexReg
                val nextCount = countReg + rjReg(sourceIndex(4, 0))
                countReg := nextCount
                when(indexReg + 1.U >= rjWidthReg) {
                    state := sReduce
                }.otherwise {
                    indexReg := indexReg + 1.U
                }
            }
            is(sReduce) {
                // Reduce count modulo the clipped destination-window width.
                when(countReg >= rdWidthReg) {
                    countReg := countReg - rdWidthReg
                }.otherwise {
                    indexReg := 0.U
                    state := sRotate
                }
            }
            is(sRotate) {
                val unwrappedSource = indexReg + countReg
                val sourceOffset = Mux(unwrappedSource >= rdWidthReg,
                    unwrappedSource - rdWidthReg, unwrappedSource)
                val sourceIndex = Cat(0.U(1.W), rdBaseReg) + sourceOffset
                val destinationIndex = Cat(0.U(1.W), rdBaseReg) + indexReg
                resultBits(destinationIndex(4, 0)) :=
                    oldRdReg(sourceIndex(4, 0))
                when(indexReg + 1.U >= rdWidthReg) {
                    state := sDone
                }.otherwise {
                    indexReg := indexReg + 1.U
                }
            }
            is(sDone) {
                when(io.consume) {
                    state := sIdle
                }
            }
        }
    }
}
