# `ldmaxu.w` 的数据来源与立即数扩展

本文解释 `CUSTOM_MEMORY_INSTRUCTION_TEMPLATE.md` 中的 `ldmaxu.w` 为什么能在 M2 阶段直接使用 `rdata` 和 `pipe.src2_value`，以及为什么该处不需要再次对 SI12 做符号扩展。

相关代码：

```scala
val loadResult = MuxLookup(pipe.lsOp, rdata)(Seq(
    LsOp.LD_B -> Cat(Fill(24, byte(7)), byte),
    LsOp.LD_BU -> Cat(0.U(24.W), byte),
    LsOp.LD_H -> Cat(Fill(16, half(15)), half),
    LsOp.LD_HU -> Cat(0.U(16.W), half),
    LsOp.LD_W -> rdata,
    LsOp.LDMAXU_W -> rdata
))

val finalLoadResult = Mux(pipe.lsOp === LsOp.LDMAXU_W,
    Mux(rdata >= pipe.src2_value, rdata, pipe.src2_value),
    loadResult)

when(m2Reg.valid(lane) && m2Reg.lane(lane).waitDcache &&
        io.dcache(lane).data_ok) {
    m2Out.lane(lane).dcacheDone := true.B
    when(pipe.resFromMem) {
        m2Out.lane(lane).pipe.ex_result := finalLoadResult
    }
}
```

## 1. 完整数据流

`ldmaxu.w` 的语义为：

```text
vaddr  = GR[rj] + SignExtend(si12)
loaded = Memory[vaddr, 4 bytes]
result = unsigned_max(loaded, old_GR[rd])
GR[rd] = result
```

在流水线中的实际流动为：

```text
指令中的 si12
    -> Decoder 符号扩展成 32 位 dec.imm
    -> ID/IS 保存为 d.imm
    -> EX 保存为 pipe.imm，并计算 GR[rj] + pipe.imm
    -> M1 使用虚拟地址进行对齐、TLB 和 Cache 访问
    -> M2 从 DCache 得到 rdata
    -> unsigned_max(rdata, pipe.src2_value)
    -> 将最终值写回 rd
```

因此，在 M2 阶段：

- `rdata` 已经是目标地址返回的内存数据；
- `pipe.src2_value` 已经是旧 `GR[rd]`，并经过统一的冒险检测和前递；
- SI12 已经在更早的 EX 阶段用于地址计算，不需要在 M2 再次处理。

## 2. 为什么可以直接使用 `rdata`

M2 阶段取得 DCache 返回值：

```scala
val rdata = io.dcache(lane).rdata
```

只有以下条件成立时，代码才会接收该数据：

```scala
m2Reg.valid(lane) &&
m2Reg.lane(lane).waitDcache &&
io.dcache(lane).data_ok
```

所以这里的 `rdata` 不是地址，也不是尚未完成的 Cache 请求，而是与当前 load 请求对应的返回数据。

### 2.1 不同访问宽度为什么处理不同

DCache 返回的是一个 32 位数据字。byte 和 half load 需要根据地址低位选取部分数据：

```scala
val offset = pipe.ex_result(1, 0)

val byte = MuxLookup(offset, 0.U(8.W))(Seq(
    0.U -> rdata(7, 0),
    1.U -> rdata(15, 8),
    2.U -> rdata(23, 16),
    3.U -> rdata(31, 24)
))

val half = Mux(offset(1), rdata(31, 16), rdata(15, 0))
```

随后再根据指令类型进行符号扩展或零扩展：

| 指令 | M2 处理 |
|---|---|
| `ld.b` | 选择一个 byte，然后符号扩展 |
| `ld.bu` | 选择一个 byte，然后零扩展 |
| `ld.h` | 选择一个 half，然后符号扩展 |
| `ld.hu` | 选择一个 half，然后零扩展 |
| `ld.w` | 使用完整的 `rdata` |
| `ldmaxu.w` | 使用完整的 `rdata`，再和旧 `rd` 比较 |

`ldmaxu.w` 是 32 位 word load：

```text
loaded = Memory[vaddr, 4 bytes]
```

因此完整的 `rdata` 就是 `loaded`，无需选取 byte/half，也无需扩展。

## 3. 为什么可以直接使用 `pipe.src2_value`

`ldmaxu.w` 中的 `rd` 同时是源寄存器和目的寄存器：

```text
源：old_GR[rd]
目的：GR[rd]
```

### 3.1 Decoder 声明第二个源确实需要读取

该指令的译码表项选择：

```scala
BitPat("b001010_1010_????_????_????_?????_?????") -> row(
    AluOp.ADD,
    LsOp.LDMAXU_W,
    MduOp.NOP,
    Src1.R,
    Src2.IMM,
    Imm.SI12,
    Dst.RD,
    1.U,
    0.U,
    BrType.NOP,
    1.U,
    1.U
)
```

最后两个 `1.U` 表示：

```text
src1_read = true
src2_read = true
```

这里的 `Src2.IMM` 只表示 ALU或地址计算选择立即数，并不代表第二个寄存器不需要读取。是否存在寄存器依赖，由 `src1_read/src2_read` 单独描述。

### 3.2 ID 阶段让第二读口读取旧 `rd`

普通 R 型指令的第二个源通常来自 `rk = inst[14:10]`，但 `ldmaxu.w` 需要读取 `rd = inst[4:0]`：

```scala
val readsOldRd = dec.lsOp === LsOp.LDMAXU_W

val src2 = Mux(
    isStore || isSc || isBranch || isCsrWrite || readsOldRd,
    inst(4, 0),
    inst(14, 10)
)
```

因此 `ldmaxu.w` 的寄存器地址为：

```text
src1_addr = inst[9:5] = rj
src2_addr = inst[4:0] = rd
```

寄存器堆读出的旧 `rd` 先保存到：

```scala
d.src2_value := regfile.io.rdata(2 * lane + 1)
```

进入 issue/EX 时，又经过统一前递解析：

```scala
pipe.src2_value := resolved(2 * lane + 1)
```

因此，M2 中的 `pipe.src2_value` 是经过 RAW 冒险处理后的旧 `GR[rd]`。如果前一条指令刚刚产生 `rd`，这里取得的是前递后的最新架构值，而不是过期的寄存器堆读数。

## 4. 为什么比较可以直接写成 `rdata >= pipe.src2_value`

代码为：

```scala
Mux(rdata >= pipe.src2_value, rdata, pipe.src2_value)
```

`rdata` 和 `pipe.src2_value` 都是 `UInt(32.W)`，所以 Chisel 的 `>=` 在这里执行无符号比较，正好对应 `unsigned_max`。

例如：

```text
rdata             = 0x80000000
pipe.src2_value    = 0x7fffffff

无符号比较结果：0x80000000 更大
有符号比较结果：0x80000000 被解释为负数，反而更小
```

如果指令要求有符号最大值，则应显式转为 `SInt`：

```scala
Mux(
    rdata.asSInt >= pipe.src2_value.asSInt,
    rdata,
    pipe.src2_value
)
```

## 5. SI12 在哪里进行符号扩展

SI12 的扩展发生在 `Decoder.scala`，而不是 M2。

首先从指令中提取原始字段：

```scala
val i12 = inst(21, 10)
```

`ldmaxu.w` 的译码表选择：

```scala
Imm.SI12
```

随后统一生成32位立即数：

```scala
io.out.imm := Mux1H(Seq(
    (imm_s === Imm.UI5.asUInt)  -> Cat(0.U(27.W), inst(14, 10)),
    (imm_s === Imm.SI12.asUInt) -> Cat(Fill(20, i12(11)), i12),
    (imm_s === Imm.UI12.asUInt) -> Cat(0.U(20.W), i12),
    (imm_s === Imm.SI14.asUInt) -> Cat(Fill(16, i14(13)), i14, 0.U(2.W)),
    (imm_s === Imm.SI16.asUInt) -> Cat(Fill(14, i16(15)), i16, 0.U(2.W)),
    (imm_s === Imm.SI20.asUInt) -> Cat(i20, 0.U(12.W)),
    (imm_s === Imm.SI26.asUInt) -> Cat(Fill(4, i26(25)), i26, 0.U(2.W))
))
```

SI12 对应：

```scala
Cat(Fill(20, i12(11)), i12)
```

含义是把原始 12 位立即数的最高位 `i12(11)` 复制 20 次，填满高位：

```text
32 位立即数 = {20 个符号位, 原始 12 位立即数}
```

例如：

| 原始 SI12 | 扩展后的32位值 |
|---|---|
| `0x004` | `0x00000004` |
| `0x7ff` | `0x000007ff` |
| `0xffc` | `0xfffffffc`，即 `-4` |
| `0x800` | `0xfffff800`，即 `-2048` |

扩展后的立即数依次传递：

```scala
d.imm := dec.imm
pipe.imm := d.imm
```

最终在 EX 阶段计算地址：

```scala
val memVa = pipe.src1_value + pipe.imm
```

所以实际执行的是：

```text
memVa = GR[rj] + SignExtend(si12)
```

当指令到达 M2 时，SI12 已经完成地址计算任务。M2 处理的是该地址返回的内存数据，因此不应再次扩展 SI12。

## 6. 当前所有立即数扩展

立即数的原始字段和扩展逻辑集中在 `Decoder.scala`：

```scala
val i12 = inst(21, 10)
val i14 = inst(23, 10)
val i16 = inst(25, 10)
val i20 = inst(24, 5)
val i26 = Cat(inst(9, 0), inst(25, 10))
```

各类型含义如下：

| 类型 | 实现 | 含义和主要用途 |
|---|---|---|
| `UI5` | `Cat(0.U(27.W), inst(14,10))` | 5 位零扩展，用于立即数移位 |
| `SI12` | `Cat(Fill(20, i12(11)), i12)` | 12 位符号扩展，用于普通 load/store、`addi.w`、`slti` 等 |
| `UI12` | `Cat(0.U(20.W), i12)` | 12 位零扩展，用于 `andi/ori/xori` |
| `SI14` | `Cat(Fill(16, i14(13)), i14, 0.U(2.W))` | 14 位符号扩展后乘 4，用于 `ll.w/sc.w` |
| `SI16` | `Cat(Fill(14, i16(15)), i16, 0.U(2.W))` | 16 位符号扩展后乘 4，用于条件分支和 `jirl` |
| `SI20` | `Cat(i20, 0.U(12.W))` | 放入高 20 位，用于 `lu12i.w/pcaddu12i` |
| `SI26` | `Cat(Fill(4, i26(25)), i26, 0.U(2.W))` | 26 位符号扩展后乘 4，用于 `b/bl` |

`SI14/SI16/SI26` 末尾拼接的：

```scala
0.U(2.W)
```

等价于立即数左移两位，即乘 4。这样做是因为这些指令的目标地址按 4 字节指令或字地址对齐。

## 7. 三类数据不要混淆

在这条指令中，三个值承担不同职责：

| 数据 | 来源 | 用途 | 主要使用阶段 |
|---|---|---|---|
| `pipe.imm` | SI12 符号扩展 | 与 `GR[rj]` 相加生成地址 | EX |
| `pipe.src2_value` | 旧 `GR[rd]`，经过前递 | 与加载值求无符号最大值 | M2 |
| `rdata` | DCache 返回数据 | 作为加载得到的32位 word | M2 |

也就是说：

```text
pipe.imm         参与“从哪里加载”
rdata            表示“加载到了什么”
pipe.src2_value  表示“要和加载结果比较什么”
```

三者处于不同的数据通路，不应该在 M2 把 SI12 与返回数据处理混在一起。

## 8. 总结

`ldmaxu.w` 在 M2 可以直接使用：

```scala
rdata
pipe.src2_value
```

原因分别是：

1. `rdata` 是 `data_ok` 对应的完整 32 位 word load 返回值。
2. `pipe.src2_value` 是从 `rd` 读取并经过前递处理的旧寄存器值。
3. 两者都是 `UInt(32.W)`，直接比较得到无符号最大值。
4. SI12 已在 Decoder 中符号扩展为 32 位，并在 EX 中完成地址计算；M2 不再需要处理立即数。

检查类似自定义 load 时，可以沿以下顺序追踪：

```text
立即数扩展是否正确
    -> 地址是否在 EX 正确生成
    -> Cache 是否按正确宽度访问
    -> M2 是否在 data_ok 后处理 rdata
    -> 额外寄存器源是否正确读取和前递
    -> 最终结果是否写入 m2Out.pipe.ex_result
```
