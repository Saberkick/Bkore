# 自定义分支指令实现模板

本文档与 `bgeuand` 的实现配套，目标是拿到一条随机分支指令后，按逐层缩小范围的方式快速定位修改点。

## 1. 本模板指令

```text
助记符：bgeuand rj, rd, offs16

31          26 25                       10 9       5 4       0
+--------------+---------------------------+---------+---------+
|    011100    |          offs16           |   rj    |   rd    |
+--------------+---------------------------+---------+---------+

taken = (unsigned(GR[rj]) >= unsigned(GR[rd]))
        && ((GR[rj] & GR[rd]) != 0)

if taken:
    PC = PC + SignExtend(offs16 << 2)
else:
    PC = PC + 4
```

选择复合条件是为了同时覆盖无符号比较、位运算归约和普通条件分支的控制流；指令本身仍保持 LoongArch 的 `2R + si16` 分支格式。

## 2. 拿到题目后先写“语义卡片”

不要先搜索 `type`，先把题目压缩成以下字段：

```text
编码固定字段：
源寄存器：几个，分别位于哪些位段
目标寄存器：有/无
立即数：位段、是否有符号、是否左移
条件：有符号/无符号；比较/逻辑/位计数是否组合
taken 目标：PC 相对、寄存器相对或绝对地址
not-taken：通常 PC + 4
异常/访存/写回：通常都没有
```

分支类先做三个判断，范围会迅速缩小：

1. 是否改变 PC？不是则不要走分支通路。
2. 目标地址从哪里来？`PC + offset` 走条件分支模板，`rj + offset` 参考 `jirl`。
3. 是否写通用寄存器？普通条件分支不写；带链接的跳转才写返回地址。

## 3. 从外到内定位修改点

### 第一级：编码和合法性——`Decoder.scala`

检查：

- BitPat 固定位是否与题面逐位一致。
- 分支偏移是否复用 `Imm.SI16`；它已经完成符号扩展和左移 2 位。
- 两个源是否都置 `R1=1, R2=1`。
- `Dst.X, RWe=0, MWe=0` 是否符合无写回、无访存。
- `brType != NOP` 会自动让指令合法，不要再单独修改 `inst_valid`。

易错点：LoongArch 条件分支的第二源在 `rd[4:0]`，不是常见 3R 计算指令的 `rk[14:10]`。

### 第二级：身份能否穿过流水线——`Config.scala`、Bundle

若条件不能等价复用已有类型，就新增独立 `BrType`：

- `Config.scala`：增加 one-hot 项并扩宽生成器。
- `Decoder.scala` 的 `DecodeOut.brType`。
- `DualPipelineTypes.scala` 的 `DecodedLane.brType`。
- `Interface.scala` 的 `PipelineData.brType`。

每处宽度必须一致。建议把新枚举追加在末尾，避免无意义地改变已有 one-hot 编码。

易错点：只扩大 `OneHotGenerator` 而漏掉 Bundle，可能在 elaboration 时截断；只改 Bundle 而不扩大生成器则新类型宽度不足。

### 第三级：寄存器读取——`DualBackend.scala` 的 ID

`src1` 通常取 `inst[9:5]`。条件分支 `src2` 必须取 `inst[4:0]`。

本模板用 `dec.brType =/= BrType.NOP` 选择分支布局，不再维护易漏项的 opcode 白名单。新增正常分支时，只要译码得到非 NOP 的 `brType`，第二源就会自动选择正确位段。

易错点：译码表里写了 `R2=1` 并不代表读对了寄存器；它只表示“需要第二源”，真正的地址由 ID 级 `src2` 决定。

### 第四级：条件计算——`DualBackend.scala` 的 EX

只在 `branchTaken` 的 `MuxLookup` 增加条件：

```scala
val ltu = src1 < src2                    // UInt，天然是无符号比较
val hasCommonOne = (src1 & src2).orR
BrType.BGEUAND -> (!ltu && hasCommonOne)
```

不要把分支条件塞进 ALU，也不要自建多周期单元。普通分支必须在 EX 当拍解析，后续现成逻辑会负责：

- 计算 `PC + imm` 或 `rj + imm`。
- 比较预测结果。
- 冲刷错误路径。
- 更新分支预测器。

易错点：

- `UInt < UInt` 是无符号；有符号比较必须两边都 `.asSInt`。
- `>=u` 可写成 `!ltu`，但复合条件的括号不能漏。
- `(a & b) != 0` 是“存在共同的 1 位”，不是 `a != 0 && b != 0`。
- 分支目标基址通常是当前指令 PC，不是 `PC + 4`。

### 第五级：双发射、预测和冲刷

正常情况下不需要新增代码，只需逐项确认现有通用判定确实由 `brType != NOP` 驱动：

- issue 把它视为 branch，执行分支配对限制。
- predictor update 把非 `JIRL/B/BL` 分支视为 conditional。
- taken 时目标错误会重定向；not-taken 时错误预测也会重定向到 `PC+4`。
- 错误路径上的年轻指令不会产生体系结构副作用。

若这些位置使用 opcode 白名单，则必须改成译码属性或补齐新 opcode。

## 4. WSL 测试模板

工具链不认识自定义助记符时，用 `.word` 宏编码：

```asm
.macro bgeuand rj, rd, target
    .word 0x70000000 | ((((\target - .) >> 2) & 0xffff) << 10) \
                       | ((\rj) << 5) | (\rd)
.endm
```

测试必须同时证明“该跳时会跳”和“不该跳时不会跳”，而不是只跑到程序结尾。

最小覆盖矩阵：

| 场景 | `rj >=u rd` | 公共 1 位 | 预期 |
|---|---:|---:|---|
| 两条件均真 | 真 | 有 | taken |
| 比较为假 | 假 | 有 | not-taken |
| 逻辑为假 | 真 | 无 | not-taken |
| 相等且非零 | 真 | 有 | taken |
| 两边为零 | 真 | 无 | not-taken |
| 最高位参与无符号比较 | 分别构造 | 分别构造 | 对应结果 |
| 负偏移循环或回跳 | 对应条件 | 对应条件 | 正确回跳 |
| 紧邻生产者 | 对应条件 | 对应条件 | 验证 forwarding/RAW |
| taken 后放失败写标记 | 真 | 有 | 错误路径不得提交 |

建议 PASS/FAIL 使用醒目的固定值，并从仿真提交日志同时确认：

- 自定义编码确实执行了预期次数。
- RI 异常入口计数为 0。
- PASS 标记恰好一次。
- FAIL 标记为 0。

## 5. 固定验收顺序

1. `sbt compile`：先发现类型名、宽度和语法错误。
2. 导出 RTL：确认新逻辑出现在生成的 SystemVerilog 中。
3. 只同步生成 RTL 到 WSL 的 `IP/myCPU`。
4. 在 `software/examples/my_program` 构建测试程序。
5. 在 `sims/verilator/run_prog` 执行：

   ```bash
   ./configure.sh --run my_program --disable-trace-comp
   make clean
   make
   ```

6. 检查退出原因和 trace；不能只看到 Verilator 编译成功就算通过。

## 6. 失败时按现象倒推

| 现象 | 优先检查 |
|---|---|
| 进入 RI | BitPat、`brType` 是否非 NOP、同步的 RTL 是否最新 |
| taken/not-taken 全相反 | 条件表达式、signed/unsigned、宏中寄存器顺序 |
| 条件正确但目标错 | `offs16` 符号扩展、左移 2、基址 PC/rj |
| 第二源值异常 | ID 的 `src2` 是否读 `inst[4:0]` |
| 紧邻生产者才失败 | `src1_read/src2_read`、依赖检测和 forwarding |
| taken 后仍出现失败标记 | branch identity、redirect/flush、双发射年轻 lane kill |
| 只有负偏移失败 | 汇编宏偏移计算或 `Imm.SI16` 符号扩展 |

## 7. 提交前自查

- [ ] 编码与题面逐位一致且不冲突。
- [ ] 两源位段、读使能、比较的 signedness 正确。
- [ ] 不写 GPR、不访存、不误用 ALU/MDU。
- [ ] `brType` 所有载荷宽度一致。
- [ ] taken 与 not-taken 目标都正确。
- [ ] 条件分支会训练预测器，错误预测能冲刷年轻指令。
- [ ] 正偏移、负偏移、边界值、RAW、错误路径均测试。
- [ ] WSL 使用最新导出 RTL，PASS/FAIL/RI 三项均核对。

## 8. 本模板的实测验收记录

2026-08-20 在 WSL `/home/saberkick/chiplab` 完整执行：

```bash
cd sims/verilator/run_prog
./configure.sh --run my_program --disable-trace-comp
make clean
make
```

测试源码保留在 WSL 的 `software/examples/my_program/bgeuand.S` 与 `bgeuand_main.c`。提交 trace 的核对结果：

- `bgeuand` 共提交 16 次：10 个条件矩阵用例、1 个紧邻 RAW、4 次负偏移循环、1 个错误路径冲刷用例。
- RI 处理器入口提交 0 次。
- taken 后紧邻的年轻 `addi.w` 提交 0 次。
- PASS `0x600d600d` 出现 1 次，FAIL `0xdeaddead` 出现 0 次。
