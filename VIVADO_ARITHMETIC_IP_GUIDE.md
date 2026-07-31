# 在 chiplab 中使用 Vivado 原生乘法器和除法器 IP

本文适用于：

- CPU 源码：`D:\Develop\Archive\mycpu`
- chiplab：`D:\Develop\chiplab`
- Vivado：2026.1
- 器件：`xc7a200tfbg676-2`

## 1. 设计原则

CPU RTL 只包含两个独立黑盒：

| 功能 | RTL 模块名 | Vivado IP | 独立目录 |
|---|---|---|---|
| 乘法 | `multiplier` | Multiplier Generator 12.0 | `xilinx_ip\multiplier` |
| 除法 | `div_gen_0` | Divider Generator 5.1 | `xilinx_ip\div_gen_0` |

不要从 `D:\Develop\NOP-Core` 复制 `multiplier.xci`、DCP、VHDL 库或
`hdl/sim/synth` 输出目录。旧 XCI 由 Vivado 2019.2 生成，它会把旧版
`xbip_utils` 等 VHDL 库带入当前工程，并可能与 Vivado 2026.1 的
Divider Generator 库冲突。

`mycpu.Elaborate` 现在只生成 SystemVerilog，不再复制任何 XCI。这样 RTL
生成和 Vivado IP 生命周期互不影响。

## 2. IP 参数

### 2.1 multiplier

在 Vivado GUI 中选择 **IP Catalog → Multiplier Generator**，设置：

- Component Name：`multiplier`
- Multiplier Type：Parallel Multiplier
- Port A：Unsigned，32 位
- Port B：Unsigned，32 位
- Output：64 位完整乘积
- Multiplier Construction：Use Mults（DSP）
- Optimization Goal：Speed
- Pipeline Stages：2
- Clock Enable：关闭
- Synchronous Clear：关闭

接口必须保持：

```text
CLK, A[31:0], B[31:0], P[63:0]
```

### 2.2 div_gen_0

在 Vivado GUI 中选择 **IP Catalog → Divider Generator**，设置：

- Component Name：`div_gen_0`
- Algorithm：Radix-2
- Operand Sign：Unsigned
- Dividend/Quotient Width：32
- Divisor Width：32
- Remainder Type：Remainder
- AXI4-Stream Flow Control：Blocking
- Clocks Per Division：1
- Optimize Goal：Performance
- Latency：Automatic
- ARESETN：启用
- ACLKEN：关闭
- Output TREADY：关闭
- Divide-by-zero Detect：关闭

当前 Vivado 2026.1 自动得到 35 拍 IP 延迟。`Divider` 包装器负责
ready/valid、flush 复位以及除零结果，不应让乘法器和除法器共享 XCI 或
输出目录。

## 3. 用脚本生成两个 XCI

关闭正在编辑同一工程的 Vivado GUI，或至少确保 GUI 不会用旧的内存状态
覆盖工程文件。然后在 PowerShell 中执行：

```powershell
Set-Location D:\Develop\Archive\mycpu
$vivado = 'E:\AMDMesignTools\2026.1\Vivado\bin\vivado.bat'

& $vivado -mode batch `
  -source .\scripts\create_multiplier_ip.tcl `
  -tclargs D:\Develop\chiplab\IP\myCPU\xilinx_ip

& $vivado -mode batch `
  -source .\scripts\create_divider_ip.tcl `
  -tclargs D:\Develop\chiplab\IP\myCPU\xilinx_ip
```

应得到：

```text
D:\Develop\chiplab\IP\myCPU\xilinx_ip\multiplier\multiplier.xci
D:\Develop\chiplab\IP\myCPU\xilinx_ip\div_gen_0\div_gen_0.xci
```

日志中两个 `LOCKED` 都应为 `0`。

## 4. 接入已有 loongson.xpr

```powershell
& $vivado -mode batch `
  -source .\scripts\install_chiplab_arithmetic_ips.tcl `
  -tclargs `
    D:\Develop\chiplab\fpga\nscscc-team\run_vivado\project\loongson.xpr `
    D:\Develop\chiplab\IP\myCPU\xilinx_ip\multiplier\multiplier.xci `
    D:\Develop\chiplab\IP\myCPU\xilinx_ip\div_gen_0\div_gen_0.xci
```

脚本会：

1. 移除旧的 multiplier/divider XCI 引用和临时行为模型引用；
2. 分别加入两个 Vivado 2026.1 XCI；
3. 为 synthesis、implementation 和 simulation 生成输出产品；
4. 更新综合和仿真的编译顺序。

它不会把 NOP-Core 的 IP 文件复制进工程。

## 5. 从 create_project.tcl 重建

`D:\Develop\chiplab\fpga\nscscc-team\run_vivado\create_project.tcl` 已改为：

- 只从 `IP\myCPU` 根目录加入 `.sv`/`.v`；
- 只从 `IP\myCPU\xilinx_ip\*\*.xci` 加入两个 IP；
- 不再对整个 `IP\myCPU` 执行递归 `-scan_for_includes`。

这一点很重要：递归扫描会把 IP 自动生成的 VHDL、仿真文件和综合 wrapper
再次当作普通 RTL 加入，造成重复模块或版本库冲突。

如果要重建工程：

```powershell
Set-Location D:\Develop\chiplab\fpga\nscscc-team\run_vivado
& $vivado -mode batch -source .\create_project.tcl
```

注意：该脚本会删除并重建 `run_vivado\project`，执行前保存 GUI 中尚未落盘
的波形和工程设置。

## 6. 验证原生 XCI

```powershell
Set-Location D:\Develop\Archive\mycpu
& $vivado -mode batch `
  -source .\scripts\verify_chiplab_native_ip.tcl `
  -tclargs D:\Develop\chiplab\fpga\nscscc-team\run_vivado\project\loongson.xpr
```

成功标志：

```text
NATIVE_IP=multiplier IPDEF=xilinx.com:ip:mult_gen:12.0
NATIVE_IP=div_gen_0 IPDEF=xilinx.com:ip:div_gen:5.1
NATIVE_XSIM_COMPILE_OK=1
NATIVE_XSIM_ELABORATE_OK=1
```

并且不应出现 `VRFC 10-3006`、`VRFC 10-3032` 或 `USF-XSim-62`。

## 7. 运行 stream_copy 行为仿真

工程宏应启用 `RUN_PERF_TEST`，并保持 `SIMU_USE_DDR=0`。在 Vivado Tcl
Console 中执行：

```tcl
cd [get_property DIRECTORY [current_project]]
file copy -force ../../../../software/examples/nscscc_perf/obj/stream_copy/inst_data.bin ../inst_data.bin
restart
run all
```

这里 `../inst_data.bin` 正好是 `run_vivado\inst_data.bin`。测试平台从 XSim
工作目录使用 `../../../../../inst_data.bin` 打开同一个文件。

`mycpu_tb.sv` 已先清零 RAM，再依据文件实际字节数读取有效 word，不再在
EOF 后继续调用约 25 万次 `$fread`。

在当前 `stream_copy` 镜像中，PC 经过初始化后会反复运行
`0x1c000184`～`0x1c000194` 的 load/store/counter/branch 循环，这是正常
拷贝过程，不是死锁。修复后的 1 ms XSim 实测中，`debug_wb_pc` 从
`0x1c000000` 正常推进到该循环，未再出现 `X`。

如果重新生成 RTL 后仍看到 `debug_wb_pc=XXXXXXXX`，先确认 Vivado 工程
实际引用的是最新 `D:\Develop\chiplab\IP\myCPU\DualBackend.sv`，然后重新
执行 XSim Compile/Elaborate。不要用修改 `create_project.tcl` 或加入行为
模型来掩盖数据通路中的未知值。

## 8. 常见检查

在 Vivado Tcl Console 中：

```tcl
get_ips multiplier
get_ips div_gen_0
get_property IS_LOCKED [get_ips multiplier]
get_property IS_LOCKED [get_ips div_gen_0]
get_files -all *multiplier.xci
get_files -all *div_gen_0.xci
```

两个 `get_ips` 都必须各返回一个对象，两个 `IS_LOCKED` 都必须为 `0`，
每种 XCI 也只能有一份。

不要用以下做法处理冲突：

- 把 NOP-Core 的整个 `xilinx_ip`、`hdl`、`sim` 或 `synth` 目录复制到
  `IP\myCPU`；
- 同时加入 XCI vendor model 和 `multiplier_sim.sv`/`div_gen_0_sim.sv`；
- 递归扫描 `IP\myCPU\xilinx_ip`；
- 用 `drc.disableLUTOverUtilError` 隐藏资源错误。

旧的 NOP-Core/Vivado 2019.2 multiplier 文件已移到可恢复备份：

```text
D:\Develop\Archive\mycpu\generated\legacy-multiplier-2019.2-backup
```

该备份只用于追溯，不要重新加入当前工程。

## 9. 当前实测结果

在 Vivado 2026.1、`xc7a200tfbg676-2` 上完成的最终检查：

- 原生 `multiplier` 和 `div_gen_0` 均为 `IS_LOCKED=0`；
- XSim compile/elaborate 通过，没有 `USF-XSim-62`；
- `stream_copy` 运行 1 ms，PC 正常推进且没有 `X` 或 `$fread` EOF 警告；
- 60 MHz post-route：全设计 `WNS=+0.978 ns`、`WHS=+0.054 ns`；
- CPU 时钟域 60 MHz `WNS=+2.643 ns`；
- post-route `LUT as Logic=36,850/134,600`，不再有 `UTLZ-1`。

同一 routed DCP 的 100 MHz what-if 为 `WNS=-4.024 ns`。当前最差路径是
CPU `SramToAxiBridge` 到板级 `axi3_to_axi4_bridge` 的高扇出写数据路径；
CPU 内部最差为 `-3.606 ns`，是分支重定向/flush 到本地队列寄存器 CE 的
高扇出路径。100 MHz 尚未收敛，不能只修改 PLL 后直接用于 bitstream。

报告保存在：

```text
D:\Develop\Archive\mycpu\generated\vivado-timing-60-native-ip-scoreboard
D:\Develop\Archive\mycpu\generated\vivado-timing-100-native-ip-scoreboard-whatif
```
