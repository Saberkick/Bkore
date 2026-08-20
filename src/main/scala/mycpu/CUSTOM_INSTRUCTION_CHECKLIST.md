# 随机自定义指令赛场自查表（本核专用）

> 依据当前仓库最新提交 `4e00cb6`（`imp custom ist`，2026-08-19）中的
> `RRIWINZ` 实现整理。文中对 RRIWINZ 的描述来自题图和现有代码；对未来计算、
> 分支、访存题型的描述是依照本核现有结构做的实现推导，不代表尚未公布的赛题规格。

## 0. 拿到题目后的总路线

不要先问“它是什么 type”，先按体系结构副作用逐层缩小范围：

1. **把自然语言改写成精确语义**：读谁、写谁、立即数怎样解释、PC 是否改变、
   是否访问内存、异常和边界怎样处理。
2. **按副作用分大类**：只写通用寄存器＝计算；可能改 PC＝分支；访问数据地址＝访存。
3. **判断能否复用现有通路**：现有 ALU/MDU、现有比较与跳转、现有 LSU
   能完全表达时，只扩译码；不能表达时才扩枚举、结果选择或独立单元。
4. **沿数据实际经过的级逐级核对**：ID 译码和读寄存器 → IS 发射/冒险 → EX
   运算、地址或分支 → M1/M2 访存 → WB 写回。
5. **最后处理控制正确性**：合法指令、异常抑制副作用、停顿、前递、flush、双发射限制。
6. **先用手算定向用例，再做 C 参考模型随机对拍**。

最有用的第一问是：**“这条指令新增的体系结构状态变化是什么？”** `type` 只是让流水线
把这项变化路由到正确硬件的控制标签，本身不是实现。

---

## 1. 先把题目压成一张“语义合同”

拿到指令说明后，先填完下面各项。任何一项填不出来，都应回到题面查边界描述，不能靠
RTL 猜。

### 1.1 编码合同

- [ ] 固定位段和 opcode 是哪些位？`BitPat` 中只有真正可变的位才能写 `?`。
- [ ] `rd/rj/rk` 分别位于哪里？字段叫 `rd` 不等于它一定只写不读。
- [ ] 立即数由哪些位组成？是否拆段、重排？
- [ ] 立即数是有符号还是无符号？是否需要 `<< 2`、`<< 12` 等缩放？
- [ ] 未说明或保留位必须为 0，还是允许任意值？
- [ ] 新 opcode 是否与已有宽匹配规则重叠？

### 1.2 操作数与结果合同

- [ ] 列出所有**旧值输入**：`old(rd)`、`rj`、`rk`、PC、内存数据、CSR 等。
- [ ] 列出所有**体系结构输出**：新 `rd`、链接寄存器、PC、内存、标志或 CSR。
- [ ] 明确同名寄存器的读写顺序：例如 `rd = f(old(rd), rj)` 必须先读旧 `rd`。
- [ ] 明确 32 位截断、符号扩展、饱和、模运算、移位量屏蔽规则。
- [ ] 明确 `rd == rj`、`rd == 0`、输入寄存器为 `r0` 时的结果。

### 1.3 控制流、访存与异常合同

- [ ] PC 不变、条件改变还是无条件改变？目标基址是 PC、`rj` 还是其他值？
- [ ] 分支偏移相对当前 PC 还是 `PC+4`？是否按字对齐缩放？是否写链接值？
- [ ] 是否读/写内存？有效地址公式、宽度、符号扩展、字节序、对齐要求是什么？
- [ ] 越界、除零、未对齐、权限错误时结果是什么？是否抛异常？
- [ ] 发生异常或被更老指令 flush 时，寄存器、内存和内部多周期状态均不得留下副作用。

### 1.4 用一句伪代码验收

必须能写出没有歧义的参考伪代码。例如当前 RRIWINZ 可归纳为：

```text
rj_base = I16[4:0]
width   = I16[9:5]
rd_base = I16[14:10]
rj_width = min(width, 32 - rj_base)       // 当前 RTL 的解释
rd_width = min(width, 32 - rd_base)
k = popcount(rj[rj_base +: rj_width])
rd[rd_base +: rd_width] = ROR(old(rd)[rd_base +: rd_width], k mod rd_width)
rd 的窗口外保持 old(rd)；空窗口不变
```

题图没有解释 `I16[15]`，当前 Decoder 对它不加限制，而执行单元忽略它。比赛现场应优先按
正式题面/测试约束决定；若仍无说明，测试程序先把它编码为 0，并专门列为“规格歧义”。

---

## 2. 逐层减少范围：到底要改 CPU 哪里

## 第 1 层：只看体系结构副作用

| 问题 | 是 | 归入的主通路 |
|---|---|---|
| 是否发起数据存储器读写？ | 是 | LSU/访存；即使还做加法，也先按访存处理 |
| 否则是否可能改变下一 PC？ | 是 | 分支/跳转 |
| 否则是否只产生 GPR 结果？ | 是 | 整数计算 |
| 是否有多种副作用？ | 是 | 选主通路，再显式补链接写回、第二结果或专用状态 |

**易踩坑：**按指令名字分类。比如“load-and-add”不能只归 ALU；“compare-and-branch”不能
只归比较；真正决定改动范围的是体系结构副作用。

## 第 2 层：在大类内判断“复用”还是“新增”

### A. 整数计算

1. 现有 `ADD/SUB/AND/...` 能完整表达：只需 Decoder 新表项和正确的源、立即数、目的。
2. 单拍组合逻辑能完成，但现有 ALU 没有该操作：扩 `AluOp` 宽度和 `ALU.scala`。
3. 乘除类：优先接现有 MDU；新操作若有固定几拍延迟，可仿照乘法握手。
4. 可变长、迭代或会拉长关键路径：独立单元，仿照 `RrwinzUnit`，并阻塞 EX 到 `done`。

**易踩坑：**为了“少改文件”把复杂组合网硬塞 ALU，会影响整核时序；反过来，简单一拍
运算做成串行多周期会无谓降低性能和增加 flush 状态。

### B. 分支

1. 条件完全等价于 `==/!=/< signed/< unsigned`，目标也是 `PC+imm` 或 `rj+imm`：复用
   现有 `BrType` 通路，必要时只增加译码。
2. 条件不同：扩 `BrType` 及 EX 的 `branchTaken`。
3. 目标公式不同：同时扩 EX 的 `branchTarget`；不要只改 taken 条件。
4. 带链接：还要设置 `regWe/destReg`，并让写回数据为 `PC+4`。

**易踩坑：**只算对跳转条件却遗漏目标地址、立即数 `<<2`、负偏移符号扩展、链接写回，
或没有把它标成 branch，导致发射限制和预测器更新不工作。

### C. 访存

1. 地址仍是 `rj + sign/zero_extend(imm)`，宽度和扩展与 `ld/st b/h/w` 相同：复用现有
   `LsOp`，通常主要改 Decoder。
2. 只是新宽度/新扩展：扩 `LsOp`，并搜索所有 `LsOp.` 使用点逐个修改。
3. 写数据需要变换：改 M1 的 `wstrb/wdata` 形成逻辑。
4. 读回数据需要变换：改 M2 的 load 格式化逻辑。
5. 多地址、读改写或原子语义：不能伪装成普通单次 load/store；需要串行化的专用 LSU
   状态机，并处理精确异常与不可分割性。

**易踩坑：**只算出地址就以为完成。访存还包含对齐检查、TLB/权限、cacheable/uncached、
请求握手、字节写使能、读回扩展和异常时禁止写回。

## 第 3 层：把新语义映射到本核控制字段

`Decoder.scala` 的一行 decode table 实际含义如下：

| 字段 | 决定什么 | 改错后的典型现象 |
|---|---|---|
| `AluOp` | 普通 EX 运算 | 结果为 0 或错误运算 |
| `LsOp` | load/store 宽度与格式 | 地址有了但 mask、扩展或对齐错误 |
| `MduOp` | 乘除共享单元及多周期属性 | 结果时机/单双发射错误 |
| `Src1` | ALU 输入 1 选 PC 还是寄存器 | PC 相对运算错误 |
| `Src2` | ALU 输入 2 选寄存器还是立即数 | 立即数被旧寄存器值替代 |
| `Imm` | 立即数扩展和缩放方式 | 负数、边界或分支目标错误 |
| `Dst` | 写 `rd/rj/r1` | 写错寄存器 |
| `RWe/MWe` | GPR/内存副作用 | 没写回或意外写内存 |
| `BrType` | 条件、目标、预测器路径 | 顺序执行或错误重定向 |
| `R1/R2` | 冒险系统认定哪些源被读取 | 相邻依赖时偶发旧值 |

还必须注意：本核的寄存器地址并不完全由上述表生成。`DualBackend.scala` 的 ID 读口目前
固定以 `inst[9:5]` 为 src1，并按 store/branch/CSR/RRIWINZ 等条件，在 `inst[4:0]` 与
`inst[14:10]` 中选择 src2。若新指令字段布局不同，这里必须同步改；仅把 `src1_read`/
`src2_read` 置位，只表示“这个源参与冒险”，并不会把读地址自动接对。

## 第 4 层：判断控制位要穿过哪些流水级

- 只在 Decoder 当场派生、后级已有通路能表达：不新增专用位。
- ID/IS 等待时仍需识别：加到 `DecodeOut` 和 `DecodedLane`。
- EX/MEM/WB 仍需识别：再加到 `PipelineData`，并补齐所有 bundle 赋值。
- 只在 EX 使用的复杂运算仍需进入 `PipelineData`，因为 ID 与 EX 之间有 Issue Buffer。

本核专用位的真实传播链是：

```text
Decoder.DecodeOut
  -> DualBackend 中 DecodedLane d
  -> ID/IS buffer
  -> PipelineData pipe
  -> EX/M1/M1b/M2/WB
```

**易踩坑：**Bundle 中声明了字段，却漏掉 `d.xxx := dec.xxx` 或 `pipe.xxx := d.xxx`。
Chisel 可能仍能编译，但后级看到的是错误控制值。

---

## 3. 最新 RRIWINZ 提交为何改了五处

| 文件 | 这次改动的职责 | 换题时保留的判断方法 |
|---|---|---|
| `Decoder.scala` | 识别 `opcode[31:26]=111000`；保留原始 I16；声明读 `rj` 与旧 `rd`；写 `rd`；标为合法 | 所有新指令都检查编码、源、目的、立即数、合法性 |
| `DualPipelineTypes.scala` | ID/IS 缓冲保存 `isRrwinz` | 新身份跨过发射等待时必须保存 |
| `Interface.scala` | 通用流水载荷保存 `isRrwinz` | EX 或更后级仍需识别时必须保存 |
| `DualBackend.scala` | 把第二读口改为旧 `rd`；串行单发；接多周期单元；停顿、flush、consume、结果选择、前递时机 | 从“源从哪来、何时 ready、结果往哪去、被杀死怎么办”四问展开 |
| `RrwinzUnit.scala` | 锁存输入，逐位计数、求余、旋转，保持结果到消费 | 复杂计算独立单元应有 enable/flush/done/consume 协议 |

这条指令不能只增加一个 `AluOp`，原因是：它既读旧 `rd` 又写 `rd`，运算含可变窗口
popcount、模和旋转；当前实现为了面积与时序把它做成多周期，并要求 EX 在完成前保持。

### RRIWINZ 专项高危点

- [ ] `I16` 必须原样零扩展，不能误用分支 `SI16` 的符号扩展和左移 2 位。
- [ ] src1 是 `rj=inst[9:5]`，src2 特殊地是旧 `rd=inst[4:0]`。
- [ ] `src1_read/src2_read` 都为真，否则 RAW 检测漏依赖。
- [ ] 同时设置 `regWe`、`destReg=rd`、`inst_valid`。
- [ ] `rd=r0` 时可以执行但寄存器堆最终不得改变 r0。
- [ ] 多周期期间 EX 不前移；完成结果要保持到下级真正接收。
- [ ] 更老异常、分支重定向或 late fault 必须 flush 独立单元。
- [ ] 完成前不得向消费者宣称可前递。
- [ ] 当前实现对 rj 和 rd 窗口都做 32 位末端截断；正式题面若规定环绕，必须改模型与 RTL。
- [ ] `offset=0`、窗口被截成 0、计数为 0、计数恰好等于窗口宽度均应为 no-op 旋转。

---

## 4. 三类指令的“最小修改集合”

### 4.1 计算类

#### 能复用现有 ALU/MDU

- [ ] 在 Decoder 加精确编码，选对 `AluOp/MduOp`。
- [ ] 选对寄存器字段、立即数扩展、`regWe/destReg`、源读取标志。
- [ ] 加入 `inst_valid`，或通过非 NOP 的现有 type 自动成为合法指令。
- [ ] 若寄存器字段不符合现有 rj/rk/rd 习惯，修改 Backend ID 读口映射。
- [ ] 用“生产者紧邻消费者”和“消费者先进入 Issue Buffer”等用例查前递/scoreboard。

#### 需要新单拍 ALU 运算

- [ ] 扩 `Config.scala` 中 `AluOp` 的 one-hot 总宽度；宽度和操作数量必须一致。
- [ ] 扩 `ALU.scala` 的 `asBools` 解构、组合结果和 `Mux1H`。
- [ ] Decoder 加表项。
- [ ] 搜索 `AluOp` 全仓，确认没有遗漏的分类逻辑。

#### 需要新多周期运算

- [ ] 独立单元提供请求锁存、`done` 保持、`consume` 释放、`flush` 清空。
- [ ] 指令串行化或明确规定允许的 lane/配对规则。
- [ ] 把单元未完成加入 `exReadyGo`。
- [ ] 把结果加入 EX 结果选择。
- [ ] 完成前 producer 标为 not-ready；完成后检查后续级前递/写回。
- [ ] 用“下级反压时 done”“完成同拍 flush”“连续两条同类指令”查握手。

### 4.2 分支类（未来题型推导）

- [ ] 先写 `taken = ...` 和 `target = base + offset` 两个独立公式。
- [ ] 能复用现有条件就复用 `BrType`；否则扩 `BrType` 宽度与 EX `branchTaken`。
- [ ] 新目标基址/公式要改 EX `branchTarget`，并核对对齐。
- [ ] Decoder 的立即数必须按题面符号扩展和缩放。
- [ ] 正确声明比较源，尤其条件分支的第二源常编码在 `rd` 字段。
- [ ] 带链接时设置 GPR 写回，核对链接值是 `PC+4` 还是题面指定值。
- [ ] 确保 Issue Unit 把它看作 branch；不安全的 lane 配对必须阻止。
- [ ] 确保实际结果离开 EX 时才更新预测器、产生重定向和冲刷流水。
- [ ] 测 taken/not-taken、正/负偏移、最近/最远边界、错预测、后随 store 被冲刷。

### 4.3 访存类（未来题型推导）

- [ ] 明确有效地址、读写、宽度、符号扩展、对齐和写数据来源。
- [ ] 现有语义可表达时选择 `AluOp.ADD + LsOp + Src1.R + Src2.IMM`。
- [ ] load：`regWe=1, memWe=0, resFromMem=1, destReg` 正确。
- [ ] store：`regWe=0, memWe=1`，src2 必须读到 store data。
- [ ] 新 `LsOp` 后搜索全仓并修改：EX 对齐、M1 size/mask/wdata、M2 load 扩展、调试事件。
- [ ] 未对齐时设置正确异常码，并禁止 cache 请求、store、GPR 写回等副作用。
- [ ] 检查 TLB fault、cached/uncached、跨 bank 双发射限制和后级反压。
- [ ] store 后接 load、load 后立即使用、两条同 bank/异 bank 访存都要覆盖。
- [ ] 原子或多地址语义必须串行化，不能拆成可被异常/其他核观察到的普通访问。

---

## 5. WSL 下使用 `my_program` 最小测试

## 5.1 只同步 WSL 中的 RTL

本机实际用于测试的环境是：

```text
CHIPLAB_HOME=/home/saberkick/chiplab
RTL 目标=/home/saberkick/chiplab/IP/myCPU
测试程序=/home/saberkick/chiplab/software/examples/my_program
```

不要把测试 RTL 同步到 Windows 的 `D:\Develop\chiplab\IP\myCPU`。只运行
`configure.sh` 和 `make` 也不会自动把 Scala 变成新 RTL；每次改 Chisel 后仍需先导出，
再仅复制到 WSL 目标。

先在 Windows PowerShell 生成带 manifest 的 RTL 暂存目录，但不要使用 `-Sync`：

```powershell
cd D:\Develop\Archive\mycpu
sbt -batch compile
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\export_rtl.ps1
```

脚本会打印类似 `generated/exports/dual/<时间戳>` 的暂存路径。随后进入 WSL，只覆盖该
暂存目录中明确生成的 RTL 文件，不删除 WSL 目标里的其他文件：

```bash
export CHIPLAB_HOME=/home/saberkick/chiplab
RTL_STAGE=/mnt/d/Develop/Archive/mycpu/generated/exports/dual/<时间戳>
install -m 0644 "$RTL_STAGE"/*.sv "$CHIPLAB_HOME/IP/myCPU/"
install -m 0644 "$RTL_STAGE/filelist.f" "$RTL_STAGE/rtl-manifest.json" \
  "$CHIPLAB_HOME/IP/myCPU/"

test -f "$CHIPLAB_HOME/IP/myCPU/RrwinzUnit.sv"
grep -n "RrwinzUnit" "$CHIPLAB_HOME/IP/myCPU/filelist.f"
```

`sbt compile` 只检查 Scala，不会生成供 chiplab 使用的新 RTL。同步前最好用
`sha256sum` 或 `cmp` 比对；若只有个别文件不一致，只更新那些文件，避免覆盖 WSL 中
无关的本地改动。

2026-08-20 实际核验：当前提交新生成的全部 `.sv` 与 WSL RTL 字节一致；仅 WSL 的旧
`filelist.f` 漏列 `RrwinzUnit.sv`，已只在 WSL 中补齐。Windows
`D:\Develop\chiplab\IP\myCPU` 未修改。

## 5.2 建目录和 Makefile

WSL 中已经存在 `$CHIPLAB_HOME/software/examples/my_program`，包括 `Makefile`、
`main.c` 和 `rrwinz.S`。若比赛现场需要从头重建，可使用下面的 Makefile：

```make
TARGET = my_program
CFLAGS += -O2 -g
C_SRCS := $(wildcard ./*.c)
ASM_SRCS := $(wildcard ./*.S)
OBJDIR = obj
COMMON_DIR = ../../bsp
GCC_DIR = ../../../toolchains/loongson-gnu-toolchain-8.3-x86_64-loongarch32r-linux-gnusf-v2.0
PICOLIBC_DIR = ../../../toolchains/picolibc
include ../../bsp/common.mk
```

## 5.3 用 `.word` 绕过“不认识自定义助记符”的汇编器

RRIWINZ 的编码公式是：

```text
word = 0xE0000000 | (I16 << 10) | (rj << 5) | rd
I16 = rj_base | (offset << 5) | (rd_base << 10)   // I16[15] 先置 0
```

建议把自定义指令放在独立 `.S` 包装函数中，避免 C inline asm 的寄存器分配和 clobber
问题。例：固定使用 `$r12` 为旧 rd/结果、`$r13` 为 rj：

```asm
    .text

    .macro DEF_RRWINZ name, imm16
    .globl \name
    .type  \name, @function
\name:
    move  $r12, $r4                 # a0 = old_rd
    move  $r13, $r5                 # a1 = rj
    .word (0xe0000000 | (((\imm16) & 0xffff) << 10) | (13 << 5) | 12)
    move  $r4, $r12                 # return result in a0
    jirl  $r0, $r1, 0
    .size \name, .-\name
    .endm

    DEF_RRWINZ rrwinz_2100, 0x2100  # rj_base=0, offset=8, rd_base=8
```

此例的机器码应是 `0xE08401AC`。编译后必须在
`sims/verilator/run_prog/obj/my_program_obj/obj/my_program.s` 中检查该机器码和寄存器
搬运；不要只相信宏公式。

## 5.4 C 参考模型与第一条定向用例

```c
#include <stdint.h>
#include <stdio.h>

unsigned long UART_BASE = 0xbfe001e0;
unsigned long CONFREG_UART_BASE = 0xbfafff10;
unsigned long CONFREG_TIMER_BASE = 0xbfafe000;
unsigned long CONFREG_CLOCKS_PER_SEC = 100000000L;
unsigned long CORE_CLOCKS_PER_SEC = 33000000L;

extern uint32_t rrwinz_2100(uint32_t old_rd, uint32_t rj);

static uint32_t low_mask(unsigned width) {
    return width == 0 ? 0u : ((1u << width) - 1u); // width 字段最大 31
}

static uint32_t rrwinz_ref(uint32_t old_rd, uint32_t rj, uint16_t imm) {
    unsigned rj_base = imm & 31u;
    unsigned width   = (imm >> 5) & 31u;
    unsigned rd_base = (imm >> 10) & 31u;
    unsigned rjw = width < 32u - rj_base ? width : 32u - rj_base;
    unsigned rdw = width < 32u - rd_base ? width : 32u - rd_base;
    if (rdw == 0) return old_rd;

    uint32_t rj_window = (rj >> rj_base) & low_mask(rjw);
    unsigned rot = (unsigned)__builtin_popcount(rj_window) % rdw;
    if (rot == 0) return old_rd;

    uint32_t mask = low_mask(rdw);
    uint32_t win = (old_rd >> rd_base) & mask;
    uint32_t rotated = ((win >> rot) | (win << (rdw - rot))) & mask;
    return (old_rd & ~(mask << rd_base)) | (rotated << rd_base);
}

int main(void) {
    const uint32_t old_rd = 0x1234ab78u;
    const uint32_t rj = 0x0000000bu;       // 低 8 位有 3 个 1
    const uint16_t imm = 0x2100u;
    uint32_t got = rrwinz_2100(old_rd, rj);
    uint32_t expected = rrwinz_ref(old_rd, rj, imm); // 0x12347578

    if (got != expected) {
        printf("FAIL got=%08x expected=%08x\n", got, expected);
        return 1;
    }
    printf("PASS rrwinz got=%08x\n", got);
    return 0;
}
```

## 5.5 构建与运行

```bash
export CHIPLAB_HOME=/home/saberkick/chiplab
export PATH="$CHIPLAB_HOME/toolchains/loongson-gnu-toolchain-8.3-x86_64-loongarch32r-linux-gnusf-v2.0/bin:$PATH"
cd "$CHIPLAB_HOME/sims/verilator/run_prog"
./configure.sh --run my_program --disable-trace-comp
make clean_soft                 # 删除 run_prog 缓存的旧 my_program_obj
make
grep -n "inst = e00200a4" log/my_program_log/simu_trace.txt
```

- `--disable-trace-comp` 是必要的：QEMU/标准 golden model 不认识自定义 `.word`。
- 修改测试源后要 `make clean_soft`，否则 `run_prog/obj/my_program_obj` 存在时软件可能被跳过。
- 修改并重新导出 RTL 后，确认 Verilator 确实重编了 `IP/myCPU`；怀疑缓存时再执行 `make clean`。
- 非交互 WSL 可能没有加载交叉工具链 PATH；出现
  `loongarch32r-linux-gnusf-gcc: No such file or directory` 时先执行上面的 `export PATH`。
- 失败时保留 `--disable-trace-comp`，但不要关 `simu_trace`；先在 trace 中定位指令是否提交、
  写了哪个寄存器，再按 ID→EX→WB 逐级查。

### 2026-08-20 实际运行记录

现有 `my_program` 使用机器码 `0xE00200A4`：`rd=$r4`、`rj=$r5`、
`rj_base=0`、`offset=4`、`rd_base=0`。输入为：

```text
old($r4) = 0x1234567d
$r5      = 0x00000003
低 4 位 popcount = 2
ROR4(0xd, 2) = 0x7
期望结果 = 0x12345677
```

WSL Verilator 已重新编译并完成仿真，trace 证据为：

```text
pc = 1c000880, inst = e00200a4, reg = 04, val = 12345677
随后程序生成成功标志：reg = 12, val = 600d600d
test end!!
```

这证明该**单个定向用例**已动态跑通，且没有进入 Reserved Instruction handler；它不等价于
边界、冒险、flush 和随机用例已经全部通过，完整覆盖仍应执行第 6 节测试矩阵。

---

## 6. 定向测试矩阵

### 所有类别都必须测

- [ ] 目的为普通寄存器与 `r0`。
- [ ] 两个源相同、源与目的相同、连续两条写同一目的。
- [ ] 前一条产生本指令的源，本指令下一条立即消费结果。
- [ ] 指令位于双发射 packet 的 lane0/lane1 候选位置。
- [ ] 前后有 load、store、branch、异常指令，检查停顿和 flush。
- [ ] 最小值、最大值、0、全 1、交替位和随机值。
- [ ] 至少 1000 组固定 seed 随机输入与 C 参考模型比较。

### 计算类额外测试

- [ ] 有符号/无符号临界值：`0x7fffffff/0x80000000/0xffffffff`。
- [ ] 移位 0、31、超范围时的题面规定。
- [ ] 多周期指令连续出现、完成时下级反压、执行中被更老分支/异常冲刷。

### 分支类额外测试

- [ ] taken 与 not-taken；相等、刚小于、刚大于。
- [ ] 正偏移、负偏移、边界偏移；目标地址低两位。
- [ ] 带/不带链接；链接寄存器同时作为比较源。
- [ ] 分支后的 store 在错路径上不得真正写内存。
- [ ] 连续执行让预测器从冷启动到命中，但无论预测如何体系结构结果都相同。

### 访存类额外测试

- [ ] 每个合法 byte offset；所有未对齐组合。
- [ ] byte/half load 的正数与最高位为 1，检查符号/零扩展。
- [ ] store byte enable 与复制后的 `wdata`。
- [ ] cache line、页边界附近；cached 与 uncached 地址。
- [ ] 地址/TLB 异常时不得写 GPR、不得修改内存。
- [ ] load-use、store-load、同 bank 双访存、异 bank 双访存。

---

## 7. 提交前最后一遍 grep 清单

```bash
rg -n "NewInst|isNewInst|AluOp|LsOp|MduOp|BrType" src/main/scala/mycpu
rg -n "src1_read|src2_read|raddr|destReg|regWriteEn" src/main/scala/mycpu
rg -n "exReadyGo|serializing|flush|consume|Producer" src/main/scala/mycpu
rg -n "lsOp|wstrb|wdata|access_size|Alignment|branchTaken|branchTarget" src/main/scala/mycpu
```

最终逐项确认：

- [ ] 编码只匹配新指令，没有吞掉已有指令。
- [ ] 合法性、源地址、源读取标志、目的和立即数全正确。
- [ ] 新控制位完整穿过所需流水级。
- [ ] 结果在正确级产生并在正确时刻可前递/写回。
- [ ] 冒险、双发射、停顿和反压不丢指令、不重复执行。
- [ ] branch/exception flush 后无寄存器、内存或内部状态残留。
- [ ] 访存的地址、mask、数据、扩展、对齐与异常全部覆盖。
- [ ] Chisel 编译通过，RTL 已重新生成并同步，Verilator 用的是新文件。
- [ ] 手算定向用例通过，固定 seed 随机参考模型通过。
- [ ] 关闭 trace compare 的原因仅是参考模型不认识自定义指令，而不是掩盖普通指令回归。
