# 在 chiplab `run_vivado` 工程中运行 NOP-Core

本文说明如何把 `D:\Develop\NOP-Core` 生成的 NOP 处理器接入
`D:\Develop\chiplab\fpga\nscscc-team\run_vivado`，完成 Vivado 工程创建、
仿真、综合、实现和 bitstream 生成。

本文按当前本机目录和工程实测编写。已验证的环境为：

- NOP-Core：`D:\Develop\NOP-Core`
- chiplab：`D:\Develop\chiplab`
- Vivado：2026.1
- FPGA：`xc7a200tfbg676-2`
- Java：21.0.11
- sbt launcher：1.10.7

> 注意：`create_project.tcl` 开头会递归删除 `run_vivado\project`，并删除
> 若干 Xilinx IP 的 `gen` 目录。工程可以重建，但其中未另存的波形配置和
> 手工设置会丢失。运行前请保存需要保留的内容。

## 1. 选择正确的 NOP-Core 顶层

NOP-Core 有两个生成入口：

- `NOP.Main`：生成单个 AXI 主接口的 `mycpu_top`，与当前 chiplab
  `soc_top.v` 的 CPU 接口相匹配，本文使用这个入口。
- `NOP.MainAdapted`：生成彼此独立的 `iBus`、`dBus` 和 `udBus`，不能直接
  接到当前只接受一个 CPU AXI 接口的 `soc_top.v`。

不要在本工程中使用 `NOP.MainAdapted`。

## 2. 生成 NOP-Core Verilog

在 PowerShell 中执行：

```powershell
Set-Location D:\Develop\NOP-Core
sbt -batch "runMain NOP.Main"
```

成功时日志包含：

```text
[Progress] ... Generate Verilog
[Done]
```

生成文件为：

```text
D:\Develop\NOP-Core\build\mycpu_top.v
```

当前版本生成的文件约 7 MiB。它是一个包含 NOP 各 RTL 子模块的单体
Verilog 文件；此外仍依赖：

- 名为 `multiplier` 的 32×32→64 位两拍乘法模块；应使用目标机当前
  Vivado 的 Multiplier Generator 重新生成，不要复制 NOP-Core 的旧 XCI；
- Vivado 内建的 `xpm_memory_sdpram`。

## 3. 建立独立的 chiplab CPU 源目录

不要把 `mycpu_top.v` 直接放进现有 `D:\Develop\chiplab\IP\myCPU` 后与原
CPU 的全部 `.sv` 文件一起编译。两套设计包含同名子模块，会引起
`module ... already declared` 或错误地绑定到旧模块。

建议为 NOP 单独建目录：

```powershell
New-Item -ItemType Directory -Force `
  D:\Develop\chiplab\IP\NOP-Core\xilinx_ip | Out-Null

Copy-Item -Force `
  D:\Develop\NOP-Core\build\mycpu_top.v `
  D:\Develop\chiplab\IP\NOP-Core\mycpu_top.v

Set-Location D:\Develop\Archive\mycpu
& E:\AMDMesignTools\2026.1\Vivado\bin\vivado.bat `
  -mode batch `
  -source .\scripts\create_multiplier_ip.tcl `
  -tclargs D:\Develop\chiplab\IP\NOP-Core\xilinx_ip
```

乘法器会生成到：

```text
D:\Develop\chiplab\IP\NOP-Core\xilinx_ip\multiplier\multiplier.xci
```

每次修改 NOP-Core Scala 源码后，只需重新执行第 2 节并复制
`mycpu_top.v`。更换 Vivado 大版本或目标器件时，应在目标机重新运行
`create_multiplier_ip.tcl`。不要复制 NOP-Core 的旧 XCI，也不要复制
`target`、`project` 或全部 Scala 源码到 chiplab。

## 4. 修改 chiplab 的 CPU 文件入口

编辑：

```text
D:\Develop\chiplab\fpga\nscscc-team\run_vivado\create_project.tcl
```

删除或注释原来的这两段：

```tcl
add_files -scan_for_includes ../../../IP/myCPU

add_files -quiet [glob -nocomplain ../../../IP/myCPU/xilinx_ip/*/*.xci]
add_files -quiet [glob -nocomplain ../../../IP/myCPU/xilinx_ip/*/*.xcix]
```

换成：

```tcl
# NOP-Core generated RTL and native Vivado multiplier IP
set cpu_root [file normalize "../../../IP/NOP-Core"]
add_files -norecurse "$cpu_root/mycpu_top.v"
add_files -quiet [glob -nocomplain "$cpu_root/xilinx_ip/*/*.xci"]

# NOP cache memories instantiate Xilinx Parameterized Macros.
set_property XPM_LIBRARIES {XPM_MEMORY} [current_project]
```

使用 `-norecurse` 只加入明确的 NOP 产物，避免把备份文件或另一套 CPU
误加到同一个 fileset。

## 5. 修改 SoC 中的顶层模块名

当前文件：

```text
D:\Develop\chiplab\chip\soc_demo\nscscc-team\soc_top.v
```

在 CPU 实例附近使用的是：

```verilog
core_top u_cpu(
```

NOP-Core 生成的顶层名是 `mycpu_top`，将这一行改为：

```verilog
mycpu_top u_cpu(
```

当前 `soc_top.v` 使用命名端口连接。NOP 额外导出的
`debug0_wb_inst`、`Dretire*` 和 Difftest 等端口可以不连接，不影响
elaboration。`soc_top.v` 已连接的 AXI、`debug0_*`、`break_point`、
`infor_flag`、`reg_num`、`ws_valid` 和 `rf_rdata` 在 `NOP.Main` 生成的
顶层中均存在。

切回本仓库的 Chisel CPU 时，把模块名恢复为 `core_top`，同时把
`create_project.tcl` 的 CPU 源目录恢复为 `../../../IP/myCPU`。

## 6. 从零创建 Vivado 工程

在 `run_vivado` 目录执行：

```powershell
Set-Location D:\Develop\chiplab\fpga\nscscc-team\run_vivado
& E:\AMDMesignTools\2026.1\Vivado\bin\vivado.bat `
  -mode batch -source create_project.tcl
```

脚本会创建：

```text
D:\Develop\chiplab\fpga\nscscc-team\run_vivado\project\loongson.xpr
```

创建后先检查 IP 和顶层：

```tcl
open_project project/loongson.xpr
set_property top soc_top [current_fileset]
update_compile_order -fileset sources_1
get_ips multiplier
get_files *mycpu_top.v
```

预期 `get_ips multiplier` 和 `get_files *mycpu_top.v` 都返回一个对象，
而且：

```tcl
get_property IS_LOCKED [get_ips multiplier]
generate_target all [get_ips multiplier]
```

`IS_LOCKED` 必须为 `0`。如果为 `1`，不要继续沿用或升级 NOP-Core 的
Vivado 2019.2 XCI；应移除它，并用当前 Vivado 重新运行
`scripts/create_multiplier_ip.tcl`。

## 7. 行为仿真

用 GUI 打开工程：

```powershell
& E:\AMDMesignTools\2026.1\Vivado\bin\vivado.bat `
  D:\Develop\chiplab\fpga\nscscc-team\run_vivado\project\loongson.xpr
```

在 Vivado Tcl Console 中：

```tcl
launch_simulation
```

### 7.1 功能测试

检查：

```text
D:\Develop\chiplab\chip\soc_demo\nscscc-team\config.h
```

应启用功能测试并关闭性能测试：

```verilog
`define RUN_FUNC_TEST
// `define RUN_PERF_TEST
```

宏改变后需重新编译仿真。进入 behavioral simulation 后执行：

```tcl
source run_func_test.tcl
```

当前 `run_func_test.tcl` 除功能测试外还会继续加载一个性能测试镜像；查看
日志时要区分两次 `restart` 前后的输出。

### 7.2 性能测试

配置应为：

```verilog
// `define RUN_FUNC_TEST
`define RUN_PERF_TEST
```

需要无额外存储延迟时，再启用：

```verilog
`define RUN_PERF_NO_DELAY
```

重新启动 behavioral simulation，然后执行：

```tcl
source run_allbench.tcl
```

需要保留逐项日志时可使用：

```tcl
source run_allbench_log.tcl
```

## 8. 综合、实现和生成 bitstream

只检查综合：

```tcl
open_project project/loongson.xpr
reset_run synth_1
launch_runs synth_1 -jobs 8
wait_on_run synth_1
open_run synth_1
report_utilization -hierarchical
```

完整生成 bitstream：

```powershell
Set-Location D:\Develop\chiplab\fpga\nscscc-team\run_vivado
& E:\AMDMesignTools\2026.1\Vivado\bin\vivado.bat `
  -mode batch -source bit.tcl
```

`bit.tcl` 会等待 `impl_1` 完成，并把时序摘要写到：

```text
project\loongson.runs\impl_1\timing_summary.rpt
```

另外建议在实现后检查：

```tcl
open_run impl_1
report_drc
report_utilization -hierarchical
report_timing_summary
```

不要用：

```tcl
set_param drc.disableLUTOverUtilError 1
```

来“解决” `UTLZ-1`。它只把错误降级为警告，不能让超量设计完成合法布局。

## 9. 常见问题

### 找不到 `mycpu_top`

- 确认运行的是 `NOP.Main`。
- 确认 `build\mycpu_top.v` 已复制到 `IP\NOP-Core`。
- 确认 `create_project.tcl` 加入的是新目录，而不是旧的 `IP\myCPU`。
- 执行 `update_compile_order -fileset sources_1`。

### 找不到 `multiplier` 或 multiplier 被当成黑盒

- 确认 `get_ips multiplier` 有返回。
- 确认加入的是当前 Vivado 由 `scripts\create_multiplier_ip.tcl` 生成的
  `xilinx_ip\multiplier\multiplier.xci`。
- 确认 `IS_LOCKED=0`，然后执行 `generate_target all`。
- 不要同时加入两份同名 `multiplier.xci`。
- 不要加入 `multiplier_sim.sv` 来掩盖 XCI 或 Output Products 问题。

### 找不到 `xpm_memory_sdpram`

- 确认使用 Vivado 综合，而不是把 NOP Verilog 单独交给不含 Xilinx XPM
  库的通用综合器。
- 确认工程设置了 `XPM_LIBRARIES {XPM_MEMORY}`。
- XPM 是 Vivado 自带宏，不需要从 NOP-Core 再复制一个 Verilog 实现。

### 出现重复模块定义

这是同时编译 `IP\myCPU` 的拆分 `.sv` 和 NOP 的单体
`mycpu_top.v` 导致的。一个 Vivado fileset 中只能选择一套 CPU RTL。

### 仿真能启动但差分很快失败

- 先确认 `config.h` 的功能/性能测试宏与加载的镜像一致。
- 确认复位极性为 `aresetn` 低有效。
- 确认使用 `NOP.Main` 的单 AXI 顶层。
- 确认 multiplier 的两拍 latency 未被改动。
- 先运行功能测试，再运行性能测试；不要仅凭综合成功判断 CPU 功能正确。

## 10. 本机已验证与尚需验证的边界

本文编写时已实际执行：

```powershell
Set-Location D:\Develop\NOP-Core
sbt -batch "runMain NOP.Main"
```

生成成功，产物为 `build\mycpu_top.v`，并确认其 AXI/调试端口与当前
`soc_top.v` 的命名连接兼容；也确认生成 RTL 使用
`xpm_memory_sdpram`，并实例化名为 `multiplier` 的两拍 32×32→64 位 IP。

这不等同于声明 NOP-Core 已在当前 chiplab 工程通过全部功能、性能测试和
板级验证。完成第 7、8 节并保存仿真、DRC、利用率和时序报告后，才算完成
本机工程闭环。
