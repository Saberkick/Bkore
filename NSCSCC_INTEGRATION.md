# mycpu RTL 集成到 chiplab

目标目录为 `D:\Develop\chiplab\IP\myCPU`。只同步导出 manifest 中列出的生成
SystemVerilog；不要修改 `run_vivado`、工程 Tcl、XDC、MIG 或其他 SoC IP。

## 1. 生成并同步 RTL

在 `D:\Develop\Archive\mycpu` 执行：

```powershell
sbt -batch compile
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\export_rtl.ps1 `
  -Sync -Destination D:\Develop\chiplab\IP\myCPU
```

脚本会新建时间戳导出目录、生成 `rtl-manifest.json`，再只复制 manifest 中的文件。
它不会删除目标目录中的 XCI，也不会访问 Vivado project/imports。禁止把以下文件
加入 synthesis fileset：

- `src/test/resources/div_gen_0_sim.sv`；
- OOC 检查使用的任何 `div_gen_0_blackbox.sv`；
- NoAXI/NOP-Core 的 multiplier/divider XCI。

## 2. 除法器 IP：仍然需要

本设计仍实例化外部模块 `div_gen_0`。首次创建工程，或工程中尚无同名 IP 时，
必须按 [`src/main/scala/mycpu/DIVIDER_IP_VIVADO.md`](src/main/scala/mycpu/DIVIDER_IP_VIVADO.md)
在 Vivado IP Catalog 生成 Divider Generator，并生成 Synthesis/Simulation output
products。已有正确的 `div_gen_0.xci` 时无需重复创建，只需确认工程已加载它。

乘法器不需要 IP：MUL/MULH/MULHU 由 RTL 的 33x33 乘法表达式推断 DSP。不要复制
NOP-Core/NoAXI 的 multiplier XCI，否则可能造成模块重名、仿真编译冲突或 IP
output-products 版本不匹配。

## 3. 工程检查

在 Vivado Tcl Console 做只读检查：

```tcl
get_files -all *div_gen_0*
get_files -all *myCPU*
report_ip_status
```

应只有一个 `div_gen_0` 定义。若 XSim 报 `div_gen_0` 未找到，应为 XCI 生成
Simulation output products，而不是把测试替身复制进工程。

## 4. 仿真与时序回归

使用工程原有流程依次回归：

- `innerproduct`；
- `stream_copy`、`my_memcmp`；
- `fireye_A0`、`fireye_D1`、`loop_induction`；
- DIV/MOD、LL/SC、TLB/CACOP 功能测试。

程序镜像仍按 chiplab 原有测试流程生成和装载。不要通过复制错误路径的 bin、改
工程 Tcl 或关闭 DRC 来掩盖仿真问题。

最后运行完整 SoC synthesis/implementation：

- 60 MHz：硬门槛 `WNS >= 0`；
- 100 MHz：优化目标，保留 top-10 worst paths；
- 确认不再出现 `[DRC UTLZ-1]`；
- 不设置 `drc.disableLUTOverUtilError`。

OOC 核心综合只能用于定位 RTL 资源和组合路径；最终结论必须以 chiplab 完整设计
布局布线后的报告为准。

