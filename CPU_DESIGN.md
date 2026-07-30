# LoongArch CPU 设计说明

本文档是 `mycpu` 双发射实现的体系结构与验证基准。唯一的默认导出顶层为
`DualCoreTop`，生成的 SystemVerilog 模块名为比赛要求的 `core_top`。

本核保持 LoongArch32、32 位 AXI、16 项 TLB、8 KiB I/D Cache
以及现有 CSR/异常模型，借鉴 LLCL-MIPS 的前端分级、指令队列和
锁步双发射思想，但不包含 MIPS CP0、延迟槽或 MIPS 指令语义。

## 1. Dual 总体结构

```text
F1 → F2 → F3 → ID/Issue → EX → M1 → M2 → WB
```

- F1：维护 PC，同时查询两个取指槽的分支预测信息。
- F2：完成 DMW/TLB 翻译，访问 16 B ICache line。
- F3：从返回 Cache line 选择一至两条指令并写入指令队列。
- ID/Issue：双译码、4R2W 寄存器堆、前递、相关与结构冲突检查。
- EX：两个整数 ALU、共享乘除法器、分支解析和虚地址计算。
- M1：DTLB、权限检查、物理地址形成和 DCache 请求。
- M2：等待阻塞式 DCache 响应并完成 load 数据扩展。
- WB：最多提交两条指令，更新 GPR/CSR/TLB/LLBit 并产生精确冲刷。

后端使用含两个 lane 的锁步 `DualPacket`。lane 0 永远比 lane 1 年老；
两个 lane 一起经过 EX/M1/M2/WB，lane 1 不能越过 lane 0。设计没有
乱序执行、寄存器重命名、推测提交或非阻塞 Cache。

## 2. 双宽前端

### 2.1 DualICache

Dual ICache 为 8 KiB、2 路组相联、256 组、16 B Cache line：

```text
Tag[31:12] | Index[11:4] | Offset[3:0]
```

命中时返回完整 128 位 Cache line，F3 最多选择当前 PC 和 `PC+4`
两条指令。若 PC 位于 line 最后一个字、访问为 uncached、slot 0
预测跳转或发生取指异常，则本次只产生一条指令。

Cache miss 仍通过 32 位 AXI 执行四拍 refill；替换策略为无效路优先，
两路均有效时使用每组一位 LRU。Tag/数据 payload 无复位，独立 valid
位在复位时清零。uncached 请求携带 PC 的真实字偏移，不会错误地总是读取
Cache line 的第一个字；取指翻译/对齐异常由前端本地生成，不依赖 ICache
或 AXI 返回。

### 2.2 指令队列

前端使用 8 项 `DualInstructionQueue`：

- 每拍最多连续写入两项、连续弹出两项；
- slot 1 不允许脱离 slot 0 单独入队；
- 单发射时只弹出队首，原 slot 1 下一拍成为新的 lane 0；
- 分支纠错、异常、ERTN 和 refetch 统一清空队列；
- 未返回的旧 ICache 响应通过 discard 状态吞掉。

### 2.3 分支预测

`DualBranchPredictor` 包含：

- 两个取指槽银行；
- 总计 512 项 BTB，每个银行 128 组、2 路；
- 总计 1024 个两位 gshare 方向计数器；
- 8 位全局历史寄存器；
- 每组一位 LRU。

slot 0 预测跳转时 slot 1 不进入队列；slot 1 预测跳转时两条指令均保留。
EX 只在对应指令实际前进时训练预测器。方向或目标错误会冲刷所有年轻
指令；曾预测为分支但实际为非分支的 BTB 项会失效。

## 3. 译码与发射规则

两个 `Decoder` 并行工作，`DualIssueUnit` 只决定同一队首对能否一起发射。
以下组合允许双发射：

- 两条互不相关的普通整数指令；
- 一条普通整数指令加一条访存指令；
- 一条普通整数指令加 lane 1 分支。

以下条件强制只发射 lane 0：

- lane 0 的目的寄存器被 lane 1 读取，即同包 RAW；
- 两条指令写同一非零目的寄存器，即同包 WAW；
- lane 0 是分支或 MDU；
- lane 1 是 MDU；
- 两条都是访存；
- lane 1 分支与 lane 0 分支、访存或 MDU 配对；
- 任一指令是异常或串行化指令。

CSR、TLB、CACOP、ERTN、`ll.w` 和 `sc.w` 均为串行化指令：只有 EX、
M1、M2、WB 中不存在年老指令时才能进入 EX；它进入后，在提交或触发
refetch 之前也禁止任何年轻指令发射。

## 4. 数据相关与执行

寄存器堆为 32×32 位、4 读 2 写，`r0` 恒为零。两个写口按年龄排列，
防御性 WAW 情况下 lane 1 优先；正常发射规则不会产生同包 WAW。

ID 的源操作数按以下顺序查找最年轻生产者：

```text
EX lane1/0 → M1 lane1/0 → M2 lane1/0 → WB lane1/0 → RegFile
```

普通 ALU、已完成 MDU 和已完成 load 可以前递。load 或 CSR 结果尚未产生
时，消费者保持在指令队列。两个整数 ALU 独立，共享一个 MDU 和一个 LSU。

乘法结果在 EX 内寄存一拍。除法 wrapper 一次只接收一个请求，并根据
Divider Generator 的 AXI-Stream valid/ready 和输出 valid 工作，不依赖
固定 IP 延迟。除数为零时返回商 `0xffffffff`、余数为原被除数。

## 5. 地址翻译和存储系统

地址翻译优先级为：

1. `CRMD.DA=1 && CRMD.PG=0`：直接地址；
2. 分页模式下命中 DMW0/DMW1；
3. 查询 16 项全相联 TLB。

M1 检查 TLBR、PIL、PIS、PPI 和 PME，MAT=0 的访问标记为 uncached。
load/store 对齐错误在 EX 产生 ALE。每个 `DualPacket` 最多含一条 LSU
操作，因此 DTLB 端口 1 和 DCache 都保持单端口。

DCache 继续使用 8 KiB、2 路、16 B line、write-back/write-allocate
阻塞式实现。替换路先选择 invalid，再按每组 LRU 选择。外部仍只有一个
32 位 AXI 主接口，I/D Cache 通过 `SramToAxiBridge` 仲裁；首版不允许
乱序 AXI 或多个全局读 burst 在途。

I/D Cache CACOP、TLB 修改和 MMU CSR 修改均串行执行并在 WB refetch。

## 6. 精确提交与冲刷

提交和重定向优先级固定为：

```text
reset > exception/interrupt/ERTN > refetch > branch correction
```

- lane 0 在 lane 1 之前提交。
- lane 0 异常会抑制 lane 1 的全部体系结构写入。
- 当前发射策略把异常单独发射，因此异常最终总是作为 WB 的最老 lane。
- 中断只注入队首 lane 0，并禁止与 lane 1 同发。
- 异常跳转到 `EENTRY`，TLB refill 跳转到 `TLBRENTRY`。
- ERTN 跳转到 `ERA`。
- refetch 从提交指令的 `PC+4` 重新取指。
- WB flush 清除 EX/M1/M2、前端在途元数据和指令队列。
- EX 分支纠错只清除比分支年轻的前端/ID 内容，不清除 M1/M2 中的年老指令。

## 7. LL/SC 和 LLBCTL

Decoder 支持 LoongArch `ll.w`、`sc.w` 的 14 位有符号、左移两位立即数。
双发射核实现以下语义：

- `ll.w` 按 word load，成功提交时置 LLBit。
- `sc.w` 为条件 word store；LLBit=1 时写内存并向 `rd` 返回 1，
  LLBit=0 时不发 DCache 写请求并返回 0。
- `sc.w` 成功提交后清除 LLBit。
- CSR `LLBCTL` 地址为 `0x60`。
- 写 WCLLB 位清除 LLBit。
- ERTN 时 KLO=1 保留 LLBit一次并清除 KLO；KLO=0 清除 LLBit。

该行为覆盖 chiplab `n81_atomic_ins` 对 ROLLBIT、WCLLB 和 KLO 的检查。

## 8. 顶层接口与双提交

生成模块名为 `core_top`，保留比赛 SoC 所需的 AXI、时钟、复位、中断和
辅助端口，并提供两组提交接口：

```text
debug1_wb_pc
debug1_wb_rf_wen[3:0]
debug1_wb_rf_wnum[4:0]
debug1_wb_rf_wdata[31:0]
```

`debug0` 是年老提交，`debug1` 是年轻提交。通用 chiplab 差分环境应设置
`CPU_2CMT=y`；只连接 debug0 的 FPGA 顶层可以让 debug1 悬空。

## 9. 构建、测试与受控导出

```powershell
# 编译及 ScalaTest
sbt -batch compile
sbt -batch test

# 默认直接生成双发射 core_top
sbt -batch run

# 或显式指定输出目录
sbt -batch "runMain mycpu.Elaborate --target-dir generated/vivado-dual"

# 使用 WSL Verilator 执行不依赖 Divider IP 的定向 RTL 断言
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run_dual_smoke.ps1

# 生成带 SHA-256 manifest 的暂存 RTL；默认不修改 chiplab
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/export_rtl.ps1
```

`scripts/export_rtl.ps1` 使用时间戳目录，生成 `rtl-manifest.json`，并明确
记录仿真 Divider 未包含。只有显式给出 `-Sync -Destination <目录>` 才会
复制 manifest 列出的文件；脚本拒绝写入 Vivado `project/imports`，也不会
删除目标目录中的其他文件。

### 仿真 Divider

`src/test/resources/div_gen_0_sim.sv` 是与黑盒同端口的仿真专用模型。
它只用于 Verilator/功能仿真，允许使用 `/` 和 `%`，不得复制到
`chiplab/IP/myCPU` 或加入 Vivado synthesis file set。硬件实现仍必须在：

```text
chiplab/IP/myCPU/xilinx_ip/div_gen_0/div_gen_0.xci
```

提供真正的 Divider Generator output products。

## 10. 当前验证状态与硬件门槛

已完成的本地门槛：

- 双发射源码通过 `sbt compile`。
- 默认 `mycpu.Elaborate` 通过 CIRCT SystemVerilog elaboration。
- 完整 Dual `core_top` 在加入仿真 Divider 后通过 Verilator lint。
- 定向 RTL harness 通过，覆盖双发射相关、队列顺序、4R2W、LL/SC
  解码、CACOP refetch 和 LLBCTL/KLO。

当前 Windows 环境没有原生 Verilator，ChiselSim ScalaTest 会明确标记为
`CANCELED`，不会伪装成已执行测试；同一组关键不变量由
`scripts/run_dual_smoke.ps1` 调用 WSL Verilator 实际执行。配置原生
Windows Verilator 后可设置 `CHISEL_NATIVE_SIM=1` 启用 ScalaTest。

尚未声称完成的门槛：

- chiplab 完整功能测试与双提交差分；
- 20 项关闭 `RUN_PERF_NO_DELAY` 的正式性能回归；
- Vivado synthesis、implementation、WNS 和板级测试；
- Divider XCI 相关硬件验证。

当前 `design_pack/slack/slack.txt` 的实际记录为 24.375 ns 时钟要求、
22.001 ns 数据路径和 1.958 ns WNS，对应约 41 MHz 约束；它不能证明
57.1 MHz。后续必须在同一 Vivado 版本、器件 `xc7a200tfbg676-2`、约束和
实现策略下，将当前双发射核与 Git 中保存的单发射基线重新测量：

- 必达：完整功能通过，20 项性能程序全部通过；
- 性能目标：CPU 周期几何平均相对 Git 单发射基线改善至少 25%；
- 时序目标：50 MHz 下 WNS≥0；
- 冲刺目标：57.1 MHz 以上；
- 最终比较指标：相同环境下的实测频率除以周期数，而非单独比较 IPC
  或文档中的宣称频率。

在双发射核未通过完整 chiplab 回归前，不得把暂存 RTL 直接同步到最终
Vivado 工程目录。
