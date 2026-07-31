# 另一台设备上的 Vivado IP 导入、仿真与生成 Bitstream 指南

本文对应当前 NOP 风格改造后的双发射 CPU，目标工程为 Chiplab `nscscc-team`，器件为：

```text
xc7a200tfbg676-2
```

CPU 顶层是 `core_top`，完整 FPGA 工程的综合顶层仍然是 `soc_top`，仿真顶层是 `tb_top`。

当前工程需要两类 IP：

- Chiplab 板级 IP：PLL、DDR MIG、AXI Crossbar、JTAG AXI、VIO。
- CPU 新增 IP：`multiplier` 和 `div_gen_0`。

不要只复制 `.xpr`。该工程使用大量相对路径，另一台设备上应保持完整的 `chiplab` 目录结构。

## 最短执行流程

如果新 CPU RTL 已经加入工程，按下面顺序即可：

1. 打开完整 Chiplab 工程，确认器件为 `xc7a200tfbg676-2`。
2. 从 Design Sources 移除旧 `div_gen_0.v` 和旧 CPU RTL。
3. 用目标机当前 Vivado 分别运行 `scripts/create_multiplier_ip.tcl` 和
   `scripts/create_divider_ip.tcl`，生成两个互相独立的原生 XCI。
4. 将两个 XCI 加入工程；不要复制 NOP-Core 的旧 multiplier IP。
5. 如果板级 IP 缺失，加入第 4 节列出的 6 个 XCI。
6. `Report IP Status`，升级不兼容 IP，然后为所有 IP 执行 `Generate Output Products`。
7. 确认综合顶层为 `soc_top`，仿真顶层为 `tb_top`。
8. 先跑 Behavioral Simulation，再依次跑 Synthesis、Implementation 和 Bitstream。
9. 以 post-route `WNS>=0、TNS=0` 判断频率是否真正通过。

## 1. 推荐携带的文件

将以下内容复制到另一台设备：

```text
<chiplab_root>/
<mycpu_root>/generated/final-nop-refactor/
<mycpu_root>/scripts/create_multiplier_ip.tcl
<mycpu_root>/scripts/create_divider_ip.tcl
<mycpu_root>/scripts/install_chiplab_arithmetic_ips.tcl
```

其中 `final-nop-refactor` 至少应包含：

```text
core_top.sv
DualBackend.sv
DualFrontend.sv
DualICache.sv
Cache.sv
Divider.sv
其余同目录的所有生产用 .sv
```

不必复制 Vivado 生成的下列临时目录；它们应在新设备上重新生成：

```text
*.cache
*.gen
*.runs
*.sim
*.ip_user_files
```

不要携带固定散列的旧 `multiplier.xci`。XCI 记录 Vivado 版本和 IP
元数据，应在目标机由当前 Vivado 重新生成，并以 `IS_LOCKED=0` 作为版本
兼容性的检查条件。

## 2. 先选择工程建立方式

### 方式 A：打开已有工程

打开：

```text
<chiplab_root>/fpga/nscscc-team/run_vivado/project/loongson.xpr
```

当前本地工程由 Vivado 2025.1 保存。另一台设备优先使用 Vivado 2025.1；使用更新版本时，先备份整个工程，因为 Vivado 可能升级并改写 XCI。

### 方式 B：从 Tcl 干净重建工程

在 Vivado Tcl Console 中执行：

```tcl
cd D:/path/to/chiplab/fpga/nscscc-team/run_vivado
source create_project.tcl
```

注意：现有 `create_project.tcl` 会强制删除：

```text
run_vivado/project
板级 IP 目录下的 gen
```

因此只应在已备份的 Chiplab 副本中执行。该脚本会自动加入板级 XCI、约束、仿真文件，并设置：

```text
器件：xc7a200tfbg676-2
综合顶层：soc_top
仿真顶层：tb_top
```

如果希望 `create_project.tcl` 自动发现乘法器，应将文件放在：

```text
<chiplab_root>/IP/myCPU/xilinx_ip/multiplier/multiplier.xci
```

该脚本会扫描：

```text
<chiplab_root>/IP/myCPU/xilinx_ip/*/*.xci
```

## 3. 替换 CPU RTL 时的注意事项

如果已经导入了新 RTL，可跳到第 4 节。

旧工程把 CPU 文件复制到了工程内部的：

```text
loongson.srcs/sources_1/imports/myCPU
```

因此仅覆盖 `<chiplab_root>/IP/myCPU` 不一定能更新当前 `.xpr`。在 Vivado 的 `Sources -> Design Sources` 中：

1. 移除旧 CPU 的 `StageIF.sv`、`StageID.sv`、`StageEX.sv`、`StageMEM.sv`、`StageWB.sv`、`Ctrl.sv`、旧 Cache RAM 等文件。
2. 必须移除旧行为级 `div_gen_0.v`。
3. 选择 `Add Sources -> Add or create design sources`。
4. 加入 `final-nop-refactor` 根目录下的所有 `.sv`。
5. 不要加入 `verification` 子目录。
6. 不要加入 `multiplier_sim.sv` 或 `div_gen_0_sim.sv`。
7. 确认 `.sv` 的 `File Type` 是 `SystemVerilog`。

不要把旧流水线文件和新流水线文件混在同一 fileset 中，否则很容易出现同名模块、误选旧模块或综合了无效逻辑。

生产综合只应使用 XCI 提供的 `multiplier`、`div_gen_0`。两个 `*_sim.sv` 仅供 Verilator 等不加载 Xilinx IP 模型的仿真器使用。

## 4. 导入 Chiplab 板级 IP

完整 Chiplab 工程需要以下 6 个 XCI：

```text
<chiplab_root>/chip/soc_demo/nscscc-team/xilinx_ip/axi_crossbar_2x3/axi_crossbar_2x3.xci
<chiplab_root>/chip/soc_demo/nscscc-team/xilinx_ip/clk_pll/clk_pll.xci
<chiplab_root>/chip/soc_demo/nscscc-team/xilinx_ip/clk_pll_ddr/clk_pll_ddr.xci
<chiplab_root>/chip/soc_demo/nscscc-team/xilinx_ip/jtag_axi/jtag_axi.xci
<chiplab_root>/chip/soc_demo/nscscc-team/xilinx_ip/mig_axi_32/mig_axi_32.xci
<chiplab_root>/chip/soc_demo/nscscc-team/xilinx_ip/vio/vio_0.xci
```

GUI 操作：

1. `Flow Navigator -> Project Manager -> Add Sources`。
2. 选择 `Add or create design sources`。
3. 选中上述 XCI。
4. 加入后它们应出现在 `Sources -> IP Sources`，而不是只出现在 Simulation Sources。

也可在 Tcl Console 中执行：

```tcl
set CHIPLAB_ROOT [file normalize "D:/path/to/chiplab"]
set BOARD_IP_ROOT "$CHIPLAB_ROOT/chip/soc_demo/nscscc-team/xilinx_ip"
add_files -quiet [glob -nocomplain "$BOARD_IP_ROOT/*/*.xci"]
```

若已有 `.xpr` 中的这 6 个 IP 状态正常，无需重复导入。

## 5. 创建乘法器 `multiplier`

不要从 NOP-Core 或其他工程复制 `multiplier.xci`。在目标机的当前 Vivado
中从 IP Catalog 创建 **Multiplier Generator**，或在 PowerShell 中运行：

```powershell
Set-Location D:\path\to\mycpu
& E:\AMDMesignTools\2026.1\Vivado\bin\vivado.bat `
  -mode batch `
  -source .\scripts\create_multiplier_ip.tcl `
  -tclargs D:\path\to\chiplab\IP\myCPU\xilinx_ip
```

脚本创建：

```text
<chiplab_root>/IP/myCPU/xilinx_ip/multiplier/multiplier.xci
```

配置必须为：

| 配置 | 必须值 |
| --- | --- |
| Component Name | `multiplier` |
| 输入 | 32 位 unsigned × 32 位 unsigned |
| 输出 | 64 位 |
| Multiplier Construction | DSP / `Use_Mults` |
| Optimize Goal | Speed |
| Pipeline Stages | 2 |
| Initiation Interval | 1 |
| Clock Enable | 关闭 |
| SCLR/Reset | 关闭 |

其硬件端口必须只有：

```text
CLK
A[31:0]
B[31:0]
P[63:0]
```

如果出现 `CE`、`SCLR` 或其他端口，说明 IP 配置错误，不要修改 RTL 去迁就错误的 IP。

生成结束后，`get_property IS_LOCKED [get_ips multiplier]` 必须返回 `0`。
不要用 `Upgrade IP` 把 NOP-Core 的 Vivado 2019.2 XCI 混入当前
Divider Generator 的 VHDL 依赖库。

## 6. 创建除法器 `div_gen_0`

与 multiplier 相同，使用当前 Vivado 独立创建 divider：

```powershell
Set-Location D:\path\to\mycpu
& E:\AMDMesignTools\2026.1\Vivado\bin\vivado.bat `
  -mode batch `
  -source .\scripts\create_divider_ip.tcl `
  -tclargs D:\path\to\chiplab\IP\myCPU\xilinx_ip
```

脚本会创建并生成独立的 Divider Generator v5.1：

```text
<chiplab_root>/IP/myCPU/xilinx_ip/div_gen_0/div_gen_0.xci
```

如果用 GUI 手工创建，在 `IP Catalog` 中搜索 `Divider Generator`，组件名必须是 `div_gen_0`，配置必须为：

| 配置 | 必须值 |
| --- | --- |
| Algorithm | Radix2 |
| Dividend Width | 32 |
| Divisor Width | 32 |
| Operand Sign | Unsigned |
| Remainder Type | Remainder |
| Flow Control | Blocking |
| Clocks per Division | 1 |
| Optimize Goal | Performance |
| Latency | Automatic |
| Output TREADY | 关闭 |
| Divide-by-zero Detect | 关闭 |
| ACLKEN | 关闭 |
| ARESETn | 开启 |
| TLAST/TUSER | 全部关闭 |

生成后的端口必须包含：

```text
aclk
aresetn
s_axis_divisor_tvalid
s_axis_divisor_tready
s_axis_divisor_tdata[31:0]
s_axis_dividend_tvalid
s_axis_dividend_tready
s_axis_dividend_tdata[31:0]
m_axis_dout_tvalid
m_axis_dout_tdata[63:0]
```

RTL wrapper 依赖两个输入通道各自的 `tready`，因此不能使用没有输入 backpressure 的配置。

## 7. 必做的同名模块检查

工程中必须恰好有一个 `multiplier` 定义和一个 `div_gen_0` 定义。

先从 Vivado `Sources` 中移除旧的：

```text
div_gen_0.v
multiplier_sim.sv
div_gen_0_sim.sv
其他手写的 multiplier.v
```

可在 Tcl Console 中检查和移除旧除法文件：

```tcl
set old_div_rtl [get_files -all -quiet *div_gen_0.v]
if {[llength $old_div_rtl] != 0} {
    puts "Removing obsolete divider RTL: $old_div_rtl"
    remove_files $old_div_rtl
}

get_ips multiplier
get_ips div_gen_0
report_ip_status
```

正常结果是两个 `get_ips` 命令都各返回一个对象。

## 8. 生成所有 IP Output Products

在 `Sources -> IP Sources` 中全选 IP，右键 `Generate Output Products`。

也可执行：

```tcl
foreach ip_name {multiplier div_gen_0} {
    set ip_obj [get_ips -quiet $ip_name]
    if {[llength $ip_obj] != 1} {
        error "缺少 IP 或存在重名：$ip_name"
    }
    generate_target all $ip_obj
}

generate_target all [get_ips]
export_ip_user_files -of_objects [get_ips] -no_script -sync -force -quiet
update_compile_order -fileset sources_1
update_compile_order -fileset sim_1
report_ip_status
```

`IP Status` 中不应再有 `Locked`、`Missing output products` 或 `Incompatible`。

## 9. 综合前的工程检查

在 Tcl Console 中执行：

```tcl
puts "Part       = [get_property PART [current_project]]"
puts "Synth top  = [get_property TOP [get_filesets sources_1]]"
puts "Sim top    = [get_property TOP [get_filesets sim_1]]"
report_compile_order -fileset sources_1
```

期望输出：

```text
Part       = xc7a200tfbg676-2
Synth top  = soc_top
Sim top    = tb_top
```

约束文件应为：

```text
<chiplab_root>/fpga/nscscc-team/constraints/soc_lite.xdc
```

不要把 `core_top` 设成最终 FPGA 顶层。只有做 CPU 单模块综合或独立时序分析时，才临时使用 `core_top`。

## 10. 运行功能/性能仿真

1. 在 `soc_config.vh` 中选择一种测试：

   ```verilog
   `define RUN_FUNC_TEST
   // `define RUN_PERF_TEST
   ```

   或：

   ```verilog
   // `define RUN_FUNC_TEST
   `define RUN_PERF_TEST
   ```

2. `Flow Navigator -> Simulation -> Run Behavioral Simulation`。
3. 确认 xsim 编译日志中加载了 `multiplier` 和 `div_gen_0` 的 Xilinx 仿真模型。
4. 仿真启动后，可在 Tcl Console 中运行现有脚本。

功能测试：

```tcl
cd D:/path/to/chiplab/fpga/nscscc-team/run_vivado/project
source ../run_func_test.tcl
```

20 项性能测试：

```tcl
cd D:/path/to/chiplab/fpga/nscscc-team/run_vivado/project
source ../run_allbench.tcl
```

必须先 `cd` 到 `project` 目录，因为这两个脚本的测试程序路径是相对该目录编写的。

若 xsim 报 `module multiplier not found` 或 `module div_gen_0 not found`，不要加入本地 `*_sim.sv` 修补；应返回 `IP Sources`，检查 XCI、升级状态和 Simulation Output Products。

## 11. 运行综合、实现和 Bitstream

### GUI

依次执行：

1. `Run Synthesis`
2. `Open Synthesized Design`，先检查黑盒和资源
3. `Run Implementation`
4. `Open Implemented Design`，检查 WNS/TNS
5. 只有时序和 DRC 可接受后再 `Generate Bitstream`

### Tcl

从 `run_vivado` 目录可直接使用项目已有脚本：

```tcl
cd D:/path/to/chiplab/fpga/nscscc-team/run_vivado
source bit.tcl
```

`bit.tcl` 会打开 `project/loongson.xpr`，运行到 `write_bitstream`，并输出：

```text
project/loongson.runs/impl_1/timing_summary.rpt
```

若希望手工控制：

```tcl
reset_run synth_1
launch_runs synth_1 -jobs 8
wait_on_run synth_1

open_run synth_1
report_utilization -hierarchical \
    -file project/loongson.runs/synth_1/utilization_hier.rpt

launch_runs impl_1 -to_step write_bitstream -jobs 8
wait_on_run impl_1
open_run impl_1

report_timing_summary -delay_type min_max -max_paths 20 \
    -report_unconstrained \
    -file project/loongson.runs/impl_1/timing_summary_20.rpt
report_high_fanout_nets -timing -load_types -max_nets 50 \
    -file project/loongson.runs/impl_1/high_fanout.rpt
report_route_status \
    -file project/loongson.runs/impl_1/route_status.rpt
report_design_analysis -congestion \
    -file project/loongson.runs/impl_1/congestion.rpt
report_utilization -hierarchical \
    -file project/loongson.runs/impl_1/utilization_hier.rpt
```

验收时至少记录：

```text
CPU 实际时钟
WNS / TNS
前 20 条最差路径
route/logic delay 比例
高扇出网络
DSP、BRAM、LUT、FF
20 项性能程序周期数
```

## 12. 修改 CPU 频率

CPU 频率由板级 `clk_pll.xci` 的 `cpu_clk` 输出决定，不是仅修改 XDC 数字。

当前检查到的 Chiplab `clk_pll.xci` 中 `cpu_clk` 请求频率为 33 MHz。若另一分支或另一设备已经改为 54 MHz，以目标工程实际的 Clocking Wizard 配置和 `report_clocks` 为准。

修改方法：

1. 在 `IP Sources` 中双击 `clk_pll`。
2. 只修改 `cpu_clk` 输出频率。
3. 保持 `sys_clk` 和 DDR 相关时钟不变。
4. 重新 `Generate Output Products`。
5. `Reset Runs` 后重新综合、实现。
6. 在 Implemented Design 中执行：

   ```tcl
   report_clocks
   report_timing_summary -delay_type max -max_paths 20
   ```

`soc_lite.xdc` 已从 PLL 输出创建 `cpu_clk` generated clock。不要再对内部 `cpu_clk` 重复添加另一个 `create_clock`，也不要用 false path 或虚假 multicycle 掩盖真实违例。

判断标准：

```text
WNS >= 0 且 TNS = 0：当前频率通过时序
WNS < 0：当前频率未通过，不能仅以成功生成 bitstream 作为通过
```

频率冲刺建议按 80 MHz、100 MHz、再逐步搜索最高稳定频率进行，每次都重新跑完整实现和性能测试。

## 13. 常见错误

| 报错/现象 | 原因 | 处理 |
| --- | --- | --- |
| `module 'multiplier' not found` | XCI 未加入或 output products 未生成 | 执行 `scripts/create_multiplier_ip.tcl`，加入原生 XCI 并 Generate Output Products |
| `module 'div_gen_0' not found` | 未创建 Divider Generator | 执行 `scripts/create_divider_ip.tcl` |
| `multiplier` 重复定义 | XCI 与行为模型/旧 RTL 同时存在 | 从 Vivado fileset 移除 `multiplier_sim.sv` 或旧 `multiplier.v` |
| `div_gen_0` 重复定义 | 旧 `div_gen_0.v` 与 XCI 同时存在 | 移除旧行为级文件 |
| 找不到 divider 的 `tready` | Divider 未设为 Blocking | 按第 6 节重配 |
| 找不到 `aresetn` | Divider 未启用 ARESETn | 开启 ARESETn 并重新生成 |
| multiplier 出现 `CE/SCLR` 端口不匹配 | 乘法 IP 配置被改变 | 关闭 Clock Enable 和 SCLR |
| IP 显示 Locked | XCI 版本早于目标 Vivado | 移除旧 XCI，并用目标机当前 Vivado 重新运行对应创建脚本 |
| MIG/PLL/AXI 模块成为 black box | 板级 XCI 未导入或未生成 | 按第 4、8 节处理 |
| 只覆盖 `IP/myCPU` 后 RTL 未变化 | `.xpr` 正在使用 `imports/myCPU` 副本 | 在 Sources 中移除旧文件并重新 Add Sources |
| 仿真可过、综合重定义 | 仿真替身被错误加入 synthesis fileset | 检查 `Used In`，仿真替身不得用于 synthesis |
| Bitstream 生成但 CPU 不稳定 | WNS 为负或时钟配置不一致 | 检查 `report_clocks` 和 post-route timing |

## 14. 最终检查清单

开始实现前逐项确认：

- [ ] 完整 Chiplab 目录已复制，相对目录结构未改变。
- [ ] 器件为 `xc7a200tfbg676-2`。
- [ ] 综合顶层为 `soc_top`，仿真顶层为 `tb_top`。
- [ ] 新 CPU 根目录下所有生产 `.sv` 已加入。
- [ ] `verification` 和两个 `*_sim.sv` 未用于硬件综合。
- [ ] 旧 `div_gen_0.v` 和旧 CPU 流水线 RTL 已移除。
- [ ] 6 个板级 IP 均在 `IP Sources` 中。
- [ ] `multiplier` 配置为 32×32 unsigned、64 位输出、DSP、Speed、2 级、II=1。
- [ ] `div_gen_0` 配置为 Radix2、unsigned、Blocking、ARESETn。
- [ ] 所有 IP 已升级到兼容状态并生成 Output Products。
- [ ] Synthesis 日志没有 black box、latch、组合环和多驱动错误。
- [ ] Implementation 后 WNS≥0、TNS=0，且没有 unconstrained CPU clock path。
- [ ] 完成功能测试、20 项性能测试，并保存周期数和 post-route 报告。
