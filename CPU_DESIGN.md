# mycpu 顺序双发射设计说明

本文对应 `f1cb0f8` 基线上的性能优化版。设计保持 LoongArch32、顺序提交、现有
`core_top` AXI/调试接口，不包含乱序执行、ROB、寄存器重命名或新的 XCI。

## 1. 流水线

```text
F1(BPU read) -> F2(I-TLB/I-Cache request) -> F3(queue)
             -> ID/Issue -> Issue Buffer -> EX -> M1 -> M2 -> WB
```

- F1 向同步 BHT/BTB 发起查询；F2 获得预测结果并和 ICache 请求对齐。
- ID 同时译码两个队首项，完成寄存器读取、前递、RAW/WAW 和结构冲突检查。
- Issue Buffer 是两个 `DualPacket` 深的信用式缓冲。前端只依赖寄存的空闲容量，
  不再承受 M1/TLB/DCache 到 `popCount` 的组合 ready 链。
- Issue Buffer 中所有有效 lane 都是 scoreboard producer；结果尚未产生，因此依赖
  它们的消费者必须等待 producer 到达 EX/M1/M2 的可前递位置。
- EX/M1/M2/WB 都保持 lane0 年老、lane1 年轻的锁步双 lane；没有年轻指令越过
  未完成 load 或 div。

## 2. 两拍局部历史分支预测

`DualBranchPredictor` 参考 NoAXI-LoongArch-CPU 的局部历史思路重新实现，没有复制
其乱序、ARAT、ROB 或工程文件：

- BHT 和 BTB 均为 1024 项，按 `PC[2]` 分成 `2 x 512` 同步读 Bank。
- 每个 BTB 项保存 tag、target、条件分支、CALL 和 RETURN 标志。
- 每项使用 6 位局部历史；每 Bank 有 64 个 2 位饱和 PHT 计数器，初值弱不跳。
- 8 项推测 RAS 与 8 项提交态影子用于 CALL/RETURN 预测。
- `BL`、`JIRL rd=1` 是 CALL；`JIRL r0,r1,0` 是 RETURN。
- Fetch entry 携带 BTB hit、方向、目标和历史快照，EX 用快照训练原 PHT 项。
- BTB/PHT 更新和 RAS 恢复各延迟一拍，避免重新形成后端到前端长路径。

BHT/BTB payload 无复位并推断为 BRAM。BTB valid 使用 LUTRAM，在复位后用 512 拍
逐项清零；清零期间预测器静态预测不跳且忽略训练。这避免 1024 个复位位展开成
大量 FF/LUT，也不需要 `$readmemh` 初始化文件。

lane0 已预测跳转时只取 slot0。lane0 预测不跳分支可以和 lane1 的安全普通 ALU
配对；如果 lane0 实际跳转或预测错误，lane1 在进入 M1 前作废，不会提交或产生
访存副作用。

## 3. 发射、前递与 MDU

允许的关键双发射组合：

- 两条互不相关的普通 ALU；
- lane0 MUL + lane1 无依赖普通 ALU；
- lane0 预测不跳分支 + lane1 无依赖普通 ALU；
- 两条确定为直接地址、cacheable 且有效地址 `bit[4]` 不同的普通访存。

CSR、TLB、CACOP、LL/SC、ERTN、uncached 和其他序列化操作仍单发射。映射地址或
DMW 地址的 MAT 要到 M1 才能确定，所以当前保守地不做双访存配对。同 Bank 访存
保持 lane0 优先，lane1 留在前端队列下一拍重新作为 lane0 发射。

scoreboard 顺序为 Issue Buffer、EX、M1、M2、WB，且每一级都覆盖两个 lane。
普通 ALU 可前递；MUL 在 M1 边界寄存后从 M1 前递，避免 DSP 组合结果回到 ID。
load 在 M2 `data_ok` 同拍即可前递，否则消费者阻塞。两个 load 一快一慢时，M2
保存已完成 lane 的数据和 done，直到另一 lane 完成才整体推进。

乘法使用一个统一的 33x33 RTL 表达式，Vivado 推断 DSP，结果在 M1 寄存，允许
每拍启动一个 MUL。除法继续使用 `div_gen_0`、独占 EX 并阻塞流水；配置见
[`src/main/scala/mycpu/DIVIDER_IP_VIVADO.md`](src/main/scala/mycpu/DIVIDER_IP_VIVADO.md)。
不需要也不得加入 multiplier XCI。

## 4. 双 LSU、TLB 和 16 KiB DCache

TLB 有三个并行查询端口：一个 IF、两个 LSU，均沿用 16 项全相联比较和 4x4
分层选择。M1 对两个 lane 分别做 DMW/TLB 翻译、权限检查和异常编码；lane0 异常
会禁止 lane1 store/cache 请求等副作用。

`DualBankDCache` 包含两个现有 `Cache` 实例：

```text
总容量 16 KiB
Bank = PA[4]
每 Bank：8 KiB、2-way、256 set、16 B line
Set = PA[12:5], Tag = PA[31:13]
```

两个 Bank 的 hit 可并行。外部仍是原来的单路 AXI：read miss 用 owner 锁定到
`ret_last`，write 请求轮转仲裁且一次接受一个。`core_top` AXI 端口完全不变。

## 5. 异常、flush 与精确提交

- lane0 始终先于 lane1 提交；lane0 异常抑制 lane1 的提交和副作用。
- WB exception/interrupt/ERTN/refetch 清空前端、Issue Buffer、EX/M1/M2。
- EX 分支纠错清空前端和 Issue Buffer，并作废当前双发射包的 lane1。
- 分支预测状态不属于架构状态；RAS 从提交态影子恢复，BTB/PHT 只影响性能。

## 6. 构建与验证

```powershell
sbt -batch compile
sbt -batch test
sbt -batch "runMain mycpu.Elaborate --target-dir generated/vivado-dual"
```

本机没有原生 Verilator 时，Scala 仿真测试会明确显示 `CANCELED`；仓库的
`DualSmokeHarness` 可使用 XSim 验证基本发射、队列、BPU/BTB/RAS 不变量。完整
SoC 程序和最终 WNS 必须在 chiplab 工程中回归，不能以 OOC 综合替代。

RTL 集成和受控同步见 [`NSCSCC_INTEGRATION.md`](NSCSCC_INTEGRATION.md)。
