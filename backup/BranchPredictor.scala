package mycpu

import chisel3._
import chisel3.util._

/**
  * Branch Predictor
  *
  * 采用：
  *   1. Direct-Mapped BTB（直接映射分支目标缓冲）
  *   2. 每项一个2-bit饱和计数器（局部历史预测）
  *
  * BTB负责回答：
  *   "这是不是一条以前见过的分支？"
  *   "如果跳转，目标地址是多少？"
  *
  * Counter负责回答：
  *   "这次应该预测跳还是不跳？"
  */
class BranchPredictor(entries: Int = 32) extends Module {

    // BTB项数必须是2的幂，方便直接用PC低位做索引
    require(entries >= 2 && isPow2(entries), "BTB entry count must be a power of two")

    // index宽度
    // 例如32项BTB，需要5位Index
    private val indexWidth = log2Ceil(entries)

    // Tag宽度
    // PC[1:0]由于指令按4字节对齐，不需要存
    // Index已经使用了一部分PC位，所以剩余高位作为Tag
    private val tagWidth = 30 - indexWidth

    val io = IO(new Bundle {

        // IF阶段送来的PC
        val lookupPc = Input(UInt(32.W))

        // 是否预测跳转
        val predictedTaken = Output(Bool())

        // 预测下一条PC
        val predictedTarget = Output(UInt(32.W))

        // EX/WB阶段真实执行结果用于训练预测器
        val update = Input(new BranchPredictorUpdate())
    })

    //------------------------------------------------------------------
    // BTB存储结构
    //------------------------------------------------------------------

    // 当前项是否有效
    val valid = RegInit(VecInit(Seq.fill(entries)(false.B)))

    // Tag
    val tags = Reg(Vec(entries, UInt(tagWidth.W)))

    // Branch Target
    val targets = Reg(Vec(entries, UInt(32.W)))

    // 2-bit饱和计数器
    //
    // 00 Strong Not Taken
    // 01 Weak   Not Taken
    // 10 Weak   Taken
    // 11 Strong Taken
    val counters = Reg(Vec(entries, UInt(2.W)))

    //------------------------------------------------------------------
    // PC解析函数
    //------------------------------------------------------------------

    // 根据PC计算Index
    //
    // PC:
    // +-------------+-------+----+
    // | Tag         |Index  |00  |
    // +-------------+-------+----+
    //
    private def indexOf(pc: UInt): UInt =
        pc(indexWidth + 1, 2)

    // 取得Tag
    private def tagOf(pc: UInt): UInt =
        pc(31, indexWidth + 2)

    //------------------------------------------------------------------
    // Lookup（预测）
    //------------------------------------------------------------------

    // 根据PC得到Index
    val lookupIndex = indexOf(io.lookupPc)

    // 判断是否命中BTB
    //
    // 必须：
    //   valid == true
    //   Tag一致
    //
    val lookupHit =
        valid(lookupIndex) &&
        tags(lookupIndex) === tagOf(io.lookupPc)

    // 是否预测Taken
    //
    // 命中BTB以后，
    // Counter最高位：
    //
    // 00 -> 0
    // 01 -> 0
    // 10 -> 1
    // 11 -> 1
    //
    io.predictedTaken :=
        lookupHit &&
        counters(lookupIndex)(1)

    // 如果预测命中BTB，
    // Target就是BTB里的Target
    //
    // 否则默认PC+4
    //
    io.predictedTarget :=
        Mux(
            lookupHit,
            targets(lookupIndex),
            io.lookupPc + 4.U
        )

    //------------------------------------------------------------------
    // Update（训练）
    //------------------------------------------------------------------

    // 更新项Index
    val updateIndex = indexOf(io.update.pc)

    // 判断是否已经存在这一项
    val updateHit =
        valid(updateIndex) &&
        tags(updateIndex) === tagOf(io.update.pc)

    // 当前Counter
    val oldCounter = counters(updateIndex)

    when(io.update.valid) {

        // 如果最后发现这里根本不是Branch
        // 则BTB项失效
        when(!io.update.isBranch) {

            valid(updateIndex) := false.B

        }.otherwise {

            //----------------------------------------------------------
            // 更新BTB内容
            //----------------------------------------------------------

            valid(updateIndex) := true.B

            tags(updateIndex) :=
                tagOf(io.update.pc)

            targets(updateIndex) :=
                io.update.target

            //----------------------------------------------------------
            // 更新2-bit Counter
            //----------------------------------------------------------

            when(!updateHit) {

                //------------------------------------------------------
                // BTB第一次分配
                //
                // 根据真实结果初始化：
                //
                // Taken     -> 10
                // NotTaken  -> 01
                //
                //------------------------------------------------------

                counters(updateIndex) :=
                    Mux(
                        io.update.taken,
                        "b10".U,
                        "b01".U
                    )

            }.elsewhen(
                io.update.taken &&
                oldCounter =/= "b11".U
            ) {

                //------------------------------------------------------
                // 实际发生Taken
                //
                // Counter +1
                //
                // 00 -> 01
                // 01 -> 10
                // 10 -> 11
                // 11 -> 11
                //------------------------------------------------------

                counters(updateIndex) :=
                    oldCounter + 1.U

            }.elsewhen(
                !io.update.taken &&
                oldCounter =/= "b00".U
            ) {

                //------------------------------------------------------
                // 实际发生NotTaken
                //
                // Counter -1
                //
                // 11 -> 10
                // 10 -> 01
                // 01 -> 00
                // 00 -> 00
                //------------------------------------------------------

                counters(updateIndex) :=
                    oldCounter - 1.U
            }
        }
    }
}
