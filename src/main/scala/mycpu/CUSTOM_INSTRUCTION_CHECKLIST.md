# 随机自定义指令实战自查表（本核专用）

目标：拿到计算、分支或访存类随机指令后，按固定顺序缩小修改范围；实现时优先复制
`Rrwinz` 已验证的数据通路并替换语义，不追求最少周期或最少面积。

## 0. 固定策略

1. 先把题目写成精确伪代码和编码图。
2. 按体系结构副作用判断计算、分支或访存，不按指令名字猜。
3. 全仓搜索 `Rrwinz/isRrwinz/RrwinzUnit`，把它当作“要替换的模板清单”。
4. 新题不再要求 Rrwinz 时，统一改成新名字并删除旧单元，不保留两套支持。
5. 保留 Rrwinz 的控制协议和流水线接法，只替换源字段、运算和结果。
6. 默认使用独立单元；题目没有明确要求时，不新增 `AluOp`、不改普通 ALU。
7. 顺序必须是：Scala 编译 → 导出 RTL → 只同步 WSL → 完整仿真 → 看 trace。

## 1. 先填“指令合同”

### 1.1 编码

- [ ] 32 位编码中哪些位固定，哪些是 `rd/rj/rk/imm`？
- [ ] 固定位是否写成精确比较或精确 `BitPat`，没有把保留位误写成 `?`？
- [ ] 新编码是否与已有指令重叠？
- [ ] 测试 `.word` 是否与 RTL 使用完全相同的位段？

### 1.2 语义

- [ ] 用一行伪代码写出结果，明确有符号/无符号、32 位截断和相等规则。
- [ ] 列出所有旧值输入：`rj/rk/old(rd)/PC/内存/CSR`。
- [ ] 列出所有输出：`rd/PC/内存/CSR`。
- [ ] 明确 `rd=rj`、`rd=rk`、`rd=r0` 和源为 `r0` 时的行为。
- [ ] 明确异常或 flush 时是否允许留下任何副作用。

### 1.3 MAX.WU 示例

```text
maxwu rd, rj, rk
GR[rd] = unsigned(GR[rj]) >= unsigned(GR[rk]) ? GR[rj] : GR[rk]

[31:15] = 11100100000000000
[14:10] = rk, [9:5] = rj, [4:0] = rd
word = 0xE4000000 | (rk << 10) | (rj << 5) | rd
```

Chisel 的 `UInt` 比较是无符号比较；不要给操作数套 `SInt` 或 `$signed`。

## 2. 逐层减少修改范围

### 第 1 层：先按副作用分通路

| 问题 | 结论 | 首要检查位置 |
|---|---|---|
| 访问数据内存？ | 访存类 | Decoder、地址生成、M1/M2、mask/扩展/异常 |
| 不访存但可能改下一 PC？ | 分支类 | Decoder、比较、target、redirect/flush、链接写回 |
| 只写通用寄存器？ | 计算类 | Decoder、专用执行单元、EX 结果、写回/前递 |

易错：一条指令即使包含加法，只要还访问内存，就必须先按访存处理；只要可能改 PC，
就必须先按分支处理。

### 第 2 层：确定源字段和目的字段

本核“声明读取源”和“读口地址选对”是两件事：

- `src1_read/src2_read` 决定冒险检测是否等待生产者。
- `DualBackend` 中的 `raddr` 选择决定实际读哪个寄存器。

常规 3R：

```text
src1 = rj = inst[9:5]
src2 = rk = inst[14:10]
dest = rd = inst[4:0]
```

Rrwinz 的第二源是旧 `rd=inst[4:0]`，这是特殊情况。换成普通 3R 指令时必须删除这个
特殊选择，恢复默认 `rk` 读口。

### 第 3 层：确定新身份要穿过多远

独立执行单元需要完整传播：

```text
Decoder.DecodeOut.isNew
  -> DecodedLane.isNew
  -> ID/IS buffer
  -> PipelineData.isNew
  -> EX 识别、停顿和结果选择
```

每增加或改名一个 Bundle 字段，都要逐项检查：

- [ ] 字段声明已改。
- [ ] `d.isNew := dec.isNew` 已改。
- [ ] `pipe.isNew := d.isNew` 已改。
- [ ] 所有旧名字全仓搜索为 0。

### 第 4 层：确定执行和握手

按 Rrwinz 模板保留四个关键信号：

| 信号 | 作用 |
|---|---|
| `enable` | EX 中有效的新指令发起请求；Idle 只锁存一次 |
| `done` | 结果完成并保持，允许 EX 前移 |
| `consume` | EX 真正 fire 后释放结果，允许下一条请求 |
| `flush` | 异常、重定向或更老指令故障时清空未退休状态 |

最通用的状态机：

```text
Idle --enable--> Calculate --结果锁存--> Done --consume--> Idle
                          \--flush---------------------> Idle
```

即使运算本身一拍能完成，也可以保留这个独立单元结构，以减少随机换题时对普通 ALU 的
侵入。

### 第 5 层：确定流水线配套控制

- [ ] `exIsNew` 只在有效、无异常的 lane0 指令上成立。
- [ ] `serializing` 包含新指令，沿用 Rrwinz 的 lane0 单发策略。
- [ ] `exReadyGo` 在 `done` 前为假。
- [ ] EX 结果选择在 `isNew` 时选择专用单元结果。
- [ ] EX producer 在结果完成前标为 not-ready，不能前递假结果。
- [ ] 进入 M1 后恢复正常前递和写回。
- [ ] `flush/consume` 接到与 Rrwinz 相同的流水线事件。

## 3. 按类别检查具体修改

### 3.1 计算类

- [ ] Decoder：精确 opcode、合法指令、两个源读取、目的 `rd`、`regWe=1`。
- [ ] Backend：源读口与编码一致。
- [ ] 独立单元：锁存全部输入，只替换核心运算。
- [ ] EX：阻塞、结果选择、flush、consume、前递时机完整。
- [ ] 测试：有符号/无符号边界、相等、寄存器别名、紧邻消费者。

除非题目必须复用已有 ALU 操作，否则不扩 `AluOp`。若确实扩 ALU，才搜索并同步修改
`Config.scala`、`ALU.scala`、Decoder 和所有 `AluOp` 分类点。

### 3.2 分支类

先分别写：

```text
taken  = 条件
target = 基址 + 扩展并缩放后的偏移
```

- [ ] 比较源读口和冒险标志正确。
- [ ] 偏移相对当前 PC 还是 `PC+4` 已确认。
- [ ] 符号扩展和 `<<2` 等缩放正确。
- [ ] taken 与 target 都接入 redirect/flush。
- [ ] 带链接时写回寄存器和链接值正确。
- [ ] 仍按 Rrwinz 模板传播专用身份；运算单元输出 taken/target，而不是硬塞 ALU。
- [ ] 测 taken/not-taken、正负边界偏移、错路径 store 被冲刷。

### 3.3 访存类

- [ ] 地址公式、读写方向、宽度、符号扩展、对齐要求已写清。
- [ ] 地址源和 store data 源读口正确。
- [ ] EX 生成地址和对齐异常。
- [ ] M1 生成请求、size、`wstrb/wdata`。
- [ ] M2 完成 load 数据抽取及符号/零扩展。
- [ ] 异常时禁止 cache/store/GPR 副作用。
- [ ] 多地址或读改写指令必须串行化，不能伪装成普通单次访存。
- [ ] 测所有 byte offset、未对齐、load-use、store-load 和异常路径。

## 4. 本次 MAX.WU 实际犯错记录

以下问题来自提交 `6e3ba3a`，以后替换模板必须逐条排除：

- [ ] **文件、类和实例名没有一起改。** 后端写了 `new MaxwuUnit()`，源码却仍是
  `RrwinzUnit.scala/class RrwinzUnit`，直接导致 `not found: type MaxwuUnit`。
- [ ] **只删了一半旧算法。** 删除窗口逻辑后仍给 `rjWidthReg/rkWidthReg` 和
  `inputRjWidth/inputRkWidth` 赋值，留下 4 个未定义符号。
- [ ] **把新算法写进旧类。** 既然新题替换 Rrwinz，应删除/重命名旧单元，生成物和
  `filelist.f` 也只能保留新单元名。
- [ ] **残留无意义状态。** `sCal` 内部声明了未使用的 `isGET` 寄存器；换题时只保留
  语义需要的寄存器，不能把“可能有用”的状态留在时序逻辑里。
- [ ] **注释与代码严重不一致。** 已改成 MAX.WU，却仍写 popcount、rotate、旧 rd、I16；
  错注释会让下一次照模板修改时选错读口和数据路径。
- [ ] **没有先编译就认为替换完成。** 本次 5 个错误都能被 `sbt compile` 立即发现；
  必须把编译放在导出 RTL 之前。
- [ ] **普通 3R 不应保留 Rrwinz 的特殊 src2。** MAX.WU 的 src2 是 `rk[14:10]`，不是
  旧 `rd[4:0]`；读标志正确但读地址错误仍会算错。
- [ ] **不要用一个模糊“比较标志”代替结果。** MAX.WU 要写回较大的 32 位原值，专用
  单元结果必须是 `Mux(rj >= rk, rj, rk)`。

正确替换方式：复制 Rrwinz 的握手、串行、停顿、flush、结果选择和前递规则；删除旧
Rrwinz 运算状态；加入新指令真正需要的输入寄存器和核心运算。

## 5. 编译、导出和 WSL 同步

### 5.1 Scala 编译与 RTL 导出

```powershell
cd D:\Develop\Archive\mycpu
sbt compile
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\export_rtl.ps1
```

- [ ] `sbt compile` 必须零错误。
- [ ] 导出目录存在新单元 `.sv`。
- [ ] `filelist.f` 包含新单元且不包含被替换的旧单元。
- [ ] `rg -i "Rrwinz|isRrwinz" generated/exports/...` 应为 0。

### 5.2 只同步 WSL RTL

```bash
export CHIPLAB_HOME=/home/saberkick/chiplab
RTL_STAGE=/mnt/d/Develop/Archive/mycpu/generated/exports/dual/<时间戳>

install -m 0644 "$RTL_STAGE"/*.sv "$CHIPLAB_HOME/IP/myCPU/"
install -m 0644 "$RTL_STAGE/filelist.f" "$RTL_STAGE/rtl-manifest.json" \
  "$CHIPLAB_HOME/IP/myCPU/"
rm -f "$CHIPLAB_HOME/IP/myCPU/<被替换的旧单元>.sv"
```

不要同步到 `D:\Develop\chiplab\IP\myCPU`。单元改名或 filelist 改变后，第一次仿真要
`make clean`，不能只 `make clean_soft`。

## 6. WSL 验证

```bash
export CHIPLAB_HOME=/home/saberkick/chiplab
export PATH="$CHIPLAB_HOME/toolchains/loongson-gnu-toolchain-8.3-x86_64-loongarch32r-linux-gnusf-v2.0/bin:$PATH"
cd "$CHIPLAB_HOME/sims/verilator/run_prog"
./configure.sh --run my_program --disable-trace-comp
make clean       # RTL/单元/filelist 改过
make
```

只改测试程序时使用 `make clean_soft` 即可。

### 6.1 测试矩阵

- [ ] 基础正反顺序各一组。
- [ ] `0`、`0xffffffff`、`0x7fffffff`、`0x80000000`。
- [ ] 两个源相等。
- [ ] `rd=rj`、`rd=rk`、`rd=r0`。
- [ ] 前一条立即产生本指令的源。
- [ ] 本指令下一条立即消费结果。
- [ ] 连续两条新指令。
- [ ] 指令前后放 branch/load/store，检查 flush 和冒险。
- [ ] 至少一组随机参考模型对拍。

### 6.2 不要只看 `make` 返回 0

必须查看：

- 自定义机器码实际提交次数和每次写回值。
- RI handler 进入次数必须为 0。
- 成功标志必须出现，失败标志必须为 0。

当前 MAX.WU 测试标志：

```text
0x600d600d = 全部通过
0xdeaddead = 计算/写回错误
RI handler = 译码、合法性或编码错误
```

本次修正后的实测结果：14 次 MAX.WU 提交、RI 0 次、PASS 1 次、FAIL 0 次。

## 7. 失败后的定位顺序

1. **Scala 编译失败**：先查文件名、类名、实例名、Bundle 字段和旧算法残留。
2. **Verilator 找不到模块**：查导出目录、`filelist.f`、同步目标及旧缓存。
3. **进入 RI**：查 opcode、`inst_valid`、测试 `.word` 是否一致。
4. **所有值都像同一个源**：查 rj/rk/old-rd 的实际读口地址。
5. **边界值错**：查 UInt/SInt、扩展、截断和比较规则。
6. **单条对、相邻依赖错**：查 `src*_read`、producer ready、前递和停顿。
7. **偶发卡死**：查 `done` 是否保持、`consume` 是否只在 `exFire`、flush 是否回 Idle。
8. **修改后结果没变化**：查是否重新导出和同步 RTL，必要时完整 `make clean`。

## 8. 提交前最终清单

```bash
rg -n -i "Rrwinz|isRrwinz|NewInst|isNewInst" src/main/scala/mycpu
rg -n "src1_read|src2_read|raddr|destReg|regWe" src/main/scala/mycpu
rg -n "exReadyGo|serializing|flush|consume|Producer" src/main/scala/mycpu
```

- [ ] 旧单元名、旧控制位、旧算法变量全部清零。
- [ ] 编码、源读口、目的寄存器和合法性一致。
- [ ] 新身份完整穿过 Decoder → ID/IS → PipelineData → EX。
- [ ] 独立单元握手、串行、停顿、结果、前递和 flush 完整。
- [ ] Scala 编译、RTL 导出、WSL Verilator 和定向测试全部通过。
- [ ] `git diff --check` 无空白错误。
- [ ] 只提交源码和 checklist，不提交生成 RTL、日志或仿真缓存。
