package mycpu

import chisel3._
import chisel3.util._

/**
  * Area-oriented implementation of the competition RRWINZ instruction.
  *
  * The instruction is rare and serialized by DualBackend, so iterating over
  * the two bit windows is preferable to putting a variable popcount, modulo
  * network and 32 independent barrel selectors on the normal EX timing path.
  * Windows extending beyond bit 31 are clipped; offset zero is a no-op.
  */
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

    // 五态迭代流程：锁存请求、统计 1、求余、逐位旋转、保持结果。
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

    // I16[4:0]=rj_base、I16[9:5]=offset、I16[14:10]=rd_base。
    val requestedWidth = Cat(0.U(1.W), io.imm(9, 5))
    val rjRemaining = 32.U(6.W) - Cat(0.U(1.W), io.imm(4, 0))
    val rdRemaining = 32.U(6.W) - Cat(0.U(1.W), io.imm(14, 10))
    val inputRjWidth = Mux(requestedWidth > rjRemaining,
        rjRemaining, requestedWidth)
    val inputRdWidth = Mux(requestedWidth > rdRemaining,
        rdRemaining, requestedWidth)

    // done 会一直保持到 consume，允许后级任意时长反压。
    io.done := state === sDone
    io.result := resultBits.asUInt

    when(io.flush) {
        state := sIdle
    }.otherwise {
        switch(state) {
            is(sIdle) {
                when(io.enable) {
                    // 请求只在 Idle 锁存一次；随后 EX 可持续保持 enable。
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
                // 每拍检查 rj 窗口中的一位，累加其中为 1 的位数。
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
                // Iterative remainder keeps a general variable divider out of
                // the datapath.  countReg <= 31, so this takes at most 31
                // cycles in the deliberately low-throughput custom unit.
                when(countReg >= rdWidthReg) {
                    countReg := countReg - rdWidthReg
                }.otherwise {
                    indexReg := 0.U
                    state := sRotate
                }
            }
            is(sRotate) {
                // 每拍写回 rd 窗口的一位；窗口之外始终保留 oldRd。
                // 0000 0000 0000 0000 0000 0000 0000 0000
                val unwrappedSource = indexReg + countReg // 当前遍历次数+要旋转的位数
                val sourceOffset = Mux(unwrappedSource >= rdWidthReg, //再防止一次，遍历的时候超出去
                    unwrappedSource - rdWidthReg, unwrappedSource)
                val sourceIndex = Cat(0.U(1.W), rdBaseReg) + sourceOffset // 定位右移后的第一个数的源的位置
                val destinationIndex = Cat(0.U(1.W), rdBaseReg) + indexReg // 定位右移后第一个数的目的位置
                resultBits(destinationIndex(4, 0)) := // 注意多位宽数字要写全
                    oldRdReg(sourceIndex(4, 0))
                when(indexReg + 1.U >= rdWidthReg) {
                    state := sDone
                }.otherwise {
                    indexReg := indexReg + 1.U
                }
            }
            is(sDone) {
                // 等待后端确认 EX 前移后，才允许接收下一条 RRWINZ。
                when(io.consume) {
                    state := sIdle
                }
            }
        }
    }
}
