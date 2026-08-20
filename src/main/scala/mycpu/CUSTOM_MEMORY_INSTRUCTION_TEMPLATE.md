# 自定义访存指令实现模板

本文档与 `ldmaxu.w` 的实现配套。目标是遇到随机 load、store 或“访存后处理”题目时，先分类，再逐层缩小修改范围。

## 1. 本模板指令

```text
助记符：ldmaxu.w rd, rj, si12

31                    22 21                      10 9       5 4       0
+------------------------+--------------------------+---------+---------+
|       0010101010       |          si12            |   rj    |   rd    |
+------------------------+--------------------------+---------+---------+

vaddr  = GR[rj] + SignExtend(si12)
loaded = Memory[vaddr, 4 bytes]
result = unsigned_max(loaded, old_GR[rd])
GR[rd] = result
```

`old_GR[rd]` 指指令执行前的值。因此 `rd` 同时是源和目的寄存器；访存异常发生时，内存不应被访问，`rd` 也不能写回。

选择这个语义是为了在一条指令中覆盖：

- `2RI12` 地址生成。
- 32 位 load、对齐检查、TLB 和 Cache。
- 目的寄存器旧值作为额外源。
- M2 数据返回后的结果变换。
- load-use 转发和异常时抑制写回。

## 2. 第一层：先判断是哪一种访存题

拿到题目后先填表，不要直接搜索 `LsOp`：

| 问题 | 可能结果 |
|---|---|
| 是否读取内存 | load / 非 load |
| 是否修改内存 | store / 非 store |
| 是否同时读写内存 | 原子、LL/SC 或读改写；不能直接套普通 load/store |
| 地址基址 | `rj`、PC、其他寄存器 |
| 偏移 | 位宽、signed/unsigned、是否左移 |
| 访问宽度 | byte、half、word |
| load 扩展 | 符号扩展、零扩展、位重排、与寄存器合并 |
| store 数据 | `rd`、`rk`、立即数或计算结果 |
| GPR 写回 | 无、load 结果、成功标志、更新后的基址 |
| 对齐要求 | 1/2/4 字节；失败的异常码 |

快速缩小范围：

1. 只读内存且写 GPR：从 `ld.w` 路径开始。
2. 只写内存且不写 GPR：从 `st.w` 路径开始。
3. 既读又写内存：先确认题目是否要求原子性；普通 Cache load 后再 store 不能冒充原子指令。
4. 同时更新基址或返回状态：除 LSU 外还要检查第二写口、提交顺序，不能只增加一种 `LsOp`。

## 3. 第二层：把语义拆成五段

所有普通访存指令都可以按以下顺序分析：

```text
寄存器读取
    -> 虚拟地址生成
    -> 对齐/权限/TLB 检查
    -> Cache 请求与返回
    -> load 后处理或 store 数据组织
    -> 写回/提交
```

先确定新语义属于哪一段。比如：

- 改地址算法：EX 地址生成段。
- 新的访问宽度：EX 对齐、M1 Cache mask/size、M2 load 提取都要改。
- load 后位运算：M2 数据返回后处理。
- store 数据变换：M1 请求的 `wdata/wstrb`。
- 条件访存：还要决定条件失败时是否完全无副作用。

## 4. 第三级：逐文件定位

### 4.1 `Config.scala`：新增明确身份

不能完全等价于现有 load/store 时，新增独立 `LsOp`。本模板新增 `LDMAXU_W`，追加在已有 one-hot 项末尾。

同时扩大所有承载 `lsOp` 的宽度：

- `Decoder.scala` 的 `DecodeOut.lsOp`。
- `DualPipelineTypes.scala` 的 `DecodedLane.lsOp`。
- `Interface.scala` 的 `PipelineData.lsOp`。

易错点：只扩大生成器或只扩大 Bundle 都不完整。新 one-hot 位可能在流水线入口被截掉，最后表现为指令合法但不发 Cache 请求。

### 4.2 `Decoder.scala`：编码、读写与立即数

`ldmaxu.w` 的译码属性：

```text
ALU       = ADD（沿用地址类默认值）
LsOp      = LDMAXU_W
Src1      = R
Src2      = IMM
Imm       = SI12
Dst       = RD
regWe     = 1
memWe     = 0
src1_read = 1
src2_read = 1（旧 rd 是真实源）
```

`Src2.IMM` 表示地址计算使用立即数，不等于“不读取第二个寄存器”。实际依赖关系由 `src2_read` 和 ID 级源地址共同决定。

易错点：

- load 通常 `src2_read=0`，本指令必须为 1。
- `memWe=0`，否则会被当成 store。
- `regWe=1`，但异常路径会在统一提交逻辑中抑制写回。
- `si12` 必须符号扩展；不要把负偏移当 UI12。

### 4.3 `DualBackend.scala` ID：真正选对源寄存器

普通 load 的 `src2` 地址通常无意义，默认会取 `rk[14:10]`。本指令必须显式让第二读口取 `rd[4:0]`：

```scala
val readsOldRd = dec.lsOp === LsOp.LDMAXU_W
val src2 = Mux(... || readsOldRd, inst(4, 0), inst(14, 10))
```

易错点：译码表的 `src2_read=1` 只说明“需要读”，不会自动决定“读哪个编号”。若这里漏改，简单测试可能碰巧通过，只有旧 `rd` 与 `rk` 不同时才暴露。

### 4.4 EX：地址与对齐

地址继续复用：

```scala
memVa = src1 + imm
```

但所有 word 分类都必须包含新指令：

```scala
isWord = LD_W || LDMAXU_W || ST_W
```

这样 `vaddr[1:0] != 0` 才会产生 ALE，并在发出 Cache 请求前阻断访问。

易错点：只在 M2 把它当 word，却没在 EX 当 word，会允许未对齐访问；只做对齐却没改 Cache size，也会发出错误宽度请求。

### 4.5 M1：Cache 请求宽度与 store 数据

M1 的 `word` 判定控制：

- `access_size = 2`。
- word store 的 `wstrb = 1111`。
- store 数据是否复制/移位。

本模板是 load，只需把它加入 word 宽度，`op` 仍由 `memWe=0` 自动选择读请求。

若实现自定义 store，重点检查：

- store 数据源寄存器到底在 `rd` 还是 `rk`。
- byte/half 的 `wstrb` 是否随地址低位移动。
- `wdata` 是否按 Cache 接口要求复制到各 byte lane。
- 异常或条件失败时 `memWe` 必须被抑制。

### 4.6 M2：等数据真的返回后再后处理

普通 load 的最终值在 M2 `data_ok` 到达时生成。本指令应在这里比较：

```scala
finalLoadResult = Mux(isLdmaxu,
    Mux(rdata >= oldRd, rdata, oldRd),
    normalLoadResult)
```

Chisel 的 `UInt >= UInt` 是无符号比较。有符号最大值必须把两边都转成 `SInt`。

易错点：

- 在 EX 比较只能看到地址，尚未得到内存数据。
- 使用 `ex_result` 保存旧 `rd` 会破坏地址；旧值应留在 `src2_value`。
- 必须把最终结果写进 `m2Out.pipe.ex_result`，这样现有 M2 forwarding 才能把处理后的值送给紧邻消费者。

### 4.7 提交和 Difftest 事件

自定义 word load 仍应在 load event 中标成 word，便于 trace 核对地址和宽度。普通写回和异常抑制不应另写一套逻辑。

若参考模型不认识自定义指令，比赛测试应关闭逐指令参考比较，改用自校验程序、异常处理器和提交 trace；不能因此跳过 Cache/异常验证。

## 5. 不同题型对应哪些位置

| 题型 | 通常需要修改 |
|---|---|
| 新 word load，仅结果变换 | 译码、`LsOp`、ID 额外源、word 分类、M2 后处理 |
| 新 byte/half load | 上述位置 + M2 提取和符号/零扩展 |
| 新 store 数据变换 | 译码、源选择、word/half/byte 分类、M1 `wdata/wstrb` |
| 地址为 `rj + rk` | 译码两源、ID 源选择、EX 地址算法；不能再用 si12 |
| 地址带缩放索引 | EX 地址算法、依赖读取、测试溢出和低位对齐 |
| post-increment load/store | 可能需要第二个 GPR 写回，检查提交端口和精确异常 |
| 条件 load/store | 条件失败时 Cache 请求、异常和写回是否全部取消 |
| 原子读改写 | Cache/总线原子性、流水线序列化；不要套普通 load 模板 |

## 6. WSL 测试方法

工具链不知道自定义助记符时，用 `.word`：

```asm
.macro LDMAXU_W rd, rj, si12
    .word 0x2a800000 | (((\si12) & 0xfff) << 10) \
                       | ((\rj) << 5) | (\rd)
.endm
```

测试矩阵至少包括：

| 类别 | 必测内容 |
|---|---|
| 比较 | memory 大、old rd 大、相等、`0x80000000/0x7fffffff` |
| 地址 | `si12=0`、正偏移、负偏移 |
| Cache | 首次 miss、重复 hit、两个 bank、先 store 后 load |
| 寄存器 | `rd` 旧值、`rd=rj`、`rd=r0` |
| 流水线 | 紧邻生产者提供 old rd、紧邻消费者使用 load 结果 |
| 异常 | 未对齐 word 必须 ALE，且 rd 保持旧值 |
| 合法性 | RI 处理器不得进入 |

未对齐测试不要只看“程序没死”。安装 ALE 处理器，记录异常次数和 ERA，把 ERA 加 4 返回，再确认目标寄存器没有被故障指令修改。

## 7. 固定验收顺序

1. `sbt compile`。
2. 导出 RTL并确认生成的 M2 逻辑包含新比较。
3. 只同步生成 RTL 到 WSL `IP/myCPU`。
4. 在 `software/examples/my_program` 编写自校验测试。
5. 执行：

   ```bash
   cd sims/verilator/run_prog
   ./configure.sh --run my_program --disable-trace-comp
   make clean
   make
   ```

6. 检查提交 trace：自定义指令次数、PASS、FAIL、RI、ALE 入口和紧邻消费者结果。

## 8. 失败现象倒推

| 现象 | 优先检查 |
|---|---|
| RI | BitPat、`LsOp` 是否被截断、同步 RTL 是否最新 |
| 地址错 | rj 源、si12 符号扩展、EX `memVa` |
| 总读出 old rd | Cache 是否请求、M2 是否在 `data_ok` 后覆盖结果 |
| 总读出 memory | ID 是否把 src2 指向 rd、`src2_read` 是否为 1 |
| 无符号边界错 | 是否误用 `.asSInt` |
| byte/half/word 数据错 | access size、wstrb、M2 提取/扩展 |
| 只有负偏移错 | SI12 编码、宏的 `& 0xfff`、符号扩展 |
| 紧邻消费者错 | 最终值是否进入 `m2Out.ex_result`、load ready/forwarding |
| 未对齐却访问内存 | EX word 分类和异常前的请求抑制 |
| 异常后 rd 被改 | 写回应依赖统一异常提交控制，不要提前写寄存器 |

## 9. 提交前自查

- [ ] 编码固定字段不冲突，立即数位段正确。
- [ ] 所有源寄存器编号和读使能正确。
- [ ] load/store、访问宽度和 signedness 正确。
- [ ] 所有 `LsOp` 载荷宽度一致。
- [ ] 地址、对齐、TLB、Cache 路径均复用或明确修改。
- [ ] 后处理发生在数据返回之后。
- [ ] 异常时无 Cache 副作用、无 GPR 写回。
- [ ] miss/hit、两 bank、正负偏移、RAW/load-use、ALE/RI 均测试。

## 10. 本模板的实测验收记录

2026-08-20 在 WSL `/home/saberkick/chiplab` 完整执行 `configure.sh + make clean + make`。测试源码保留在：

```text
software/examples/my_program/ldmaxu.S
software/examples/my_program/ldmaxu_main.c
```

提交 trace 核对结果：

- 15 条正常 `ldmaxu.w` 提交，结果逐项正确。
- 覆盖首次 Cache miss、后续 hit、两个 bank、正/负 si12、unsigned 边界、`rd=rj`、`rd=r0`、旧 rd RAW、load-use 和先 store 后 load。
- 第 16 条未对齐 `ldmaxu.w` 不提交，目的寄存器保持 `0x12345678`。
- ALE 处理器恰好进入 1 次，ERA 指向故障指令。
- RI 处理器进入 0 次。
- PASS `0x600d600d` 出现 1 次，FAIL `0xdeaddead` 出现 0 次。
