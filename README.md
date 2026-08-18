# mycpu

LoongArch32 顺序双发射 CPU，生成顶层模块名为 `core_top`。本版本以 `f1cb0f8`
基线为起点，保持现有 AXI/调试接口，不引入乱序或 ROB。

主要结构：

- 两拍、双 Bank、1024 项局部历史 BPU，带 BTB/PHT 和 8 项 RAS；
- 两包深 Issue Buffer 与覆盖所有有效 producer 的 scoreboard；
- 双 LSU、三个 TLB 查询端口（IF + LSU0 + LSU1）；
- 16 KiB、2 Bank、每 Bank 8 KiB/2-way 的阻塞式 DCache；
- 外部 `mult_gen_0` 流水乘法（1 级、积寄存到 DSP 内部）；外部 `div_gen_0` 阻塞式除法。

详细设计见 [`CPU_DESIGN.md`](CPU_DESIGN.md)。chiplab 集成、受控 RTL 同步和测试
步骤见 [`NSCSCC_INTEGRATION.md`](NSCSCC_INTEGRATION.md)。除法器 IP 参数见
[`src/main/scala/mycpu/DIVIDER_IP_VIVADO.md`](src/main/scala/mycpu/DIVIDER_IP_VIVADO.md)，
乘法器 IP 参数见
[`src/main/scala/mycpu/MULTIPLIER_IP_VIVADO.md`](src/main/scala/mycpu/MULTIPLIER_IP_VIVADO.md)。

## 构建

```powershell
sbt -batch compile
sbt -batch test
sbt -batch "runMain mycpu.Elaborate --target-dir generated/vivado-dual"
```

生成并按 SHA-256 manifest 同步到 `D:\Develop\chiplab\IP\myCPU`：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\export_rtl.ps1 `
  -Sync -Destination D:\Develop\chiplab\IP\myCPU
```

导出脚本只复制 manifest 中列出的 SystemVerilog 和 `filelist.f`，不会复制测试
`div_gen_0_sim.sv`/`mult_gen_0_sim.sv`、XCI 或 OOC blackbox，也不会修改 Vivado
工程 Tcl/XDC。

## IP 要求

- 必须在 Vivado 工程中提供唯一的 `div_gen_0` Divider Generator，并生成 synthesis
  与 simulation output products。
- 必须在 Vivado 工程中提供唯一的 `mult_gen_0` Multiplier Generator（33x33 signed，
  Pipeline Stages = 1），并生成 synthesis 与 simulation output products。不得复制
  NOP-Core/NoAXI 的 multiplier XCI（避免模块重名 / 版本不匹配）。
