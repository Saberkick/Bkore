# MDU 除法 IP 配置说明（Vivado 2025.1 / 2026.1）

## 已完成的 RTL 修改

`MDU.scala` 中的 `div_gen_0` 现在只是 Vivado Divider Generator v5.1 的外部模块声明，不再内联任何使用 Verilog `/`、`%` 的实现。

`Divider` wrapper 负责：

- 同时支持 `DIV.W`、`MOD.W`、`DIV.WU`、`MOD.WU`，只实例化一个无符号除法 IP；有符号操作在 IP 两侧取绝对值并恢复符号。
- 分别遵守 dividend/divisor 两个 AXI4-Stream 输入通道的 `tvalid/tready` 握手，不依赖固定 34 拍延迟。
- 最多保留一个在途请求，符合当前阻塞式单发射流水线。
- 锁存 IP 的单拍输出，因此 EX 后级反压不会丢失商或余数。
- pipeline flush 时复位并清空 IP；`ARESETn` 额外保持三拍，满足 Divider Generator 至少两拍同步复位的要求。
- 除数为 0 时不进入 IP，保持原设计约定：商为 `0xffffffff`，余数为原被除数。

## 必须先移除旧行为级模块

Vivado 工程和任何手写 filelist 中都不能再包含旧的行为级 `div_gen_0.v`。尤其检查：

- `D:/Develop/CPU/Archive/mycpu/div_gen_0.v`
- `D:/Develop/CPU/Archive/mycpu/generated/div_gen_0.v`
- `D:/Develop/CPU/Archive/mycpu/filelist.f`
- `D:/Develop/CPU/Archive/mycpu/generated/filelist.f`

如果旧 Verilog 和 XCI 同时存在，会出现 `div_gen_0` 重定义；如果只保留旧 Verilog，本次优化不会生效。

## 推荐配置

| 选项 | 值 | 原因 |
| --- | --- | --- |
| Component Name | `div_gen_0` | 必须与 Chisel 黑盒模块名完全一致 |
| Algorithm Type | `Radix2` | 需要整数 remainder；High Radix 只支持 fractional remainder |
| Dividend Width | 32 | 商宽 32 位 |
| Divisor Width | 32 | 余数宽 32 位 |
| Remainder Type | `Remainder` | 同时输出商和整数余数 |
| Operand Sign | `Unsigned` | Scala wrapper 统一完成有符号预/后处理 |
| Clocks per Division | `1` | 当前目标是优先消除最长路径、提高 Fmax |
| Flow Control | `Blocking` | 使用两个输入 `tready`，允许真实/可变延迟 |
| Optimize Goal | `Performance` | 频率优先 |
| Output has TREADY | 关闭 | wrapper 会锁存输出，不需要 IP 输出反压 |
| Latency Configuration | `Automatic` | 让 IP 插入完整流水寄存器以获得最高频率 |
| Detect Divide-by-Zero | 关闭 | wrapper 已定义并旁路除零，避免增加 `tuser` 端口 |
| ACLKEN | 关闭 | 当前没有时钟使能端口 |
| ARESETn | 开启 | flush/全局复位需要清除在途 IP 状态 |
| TLAST/TUSER | 全部关闭 | CPU 除法不使用这些 sideband |

默认 `Clocks per Division = 1` 会使用较多寄存器和 LUT，但最有利于 Fmax。顶层时序稳定后，如果面积压力更大，可以把它改成 2、4 或 8；本次 wrapper 已实现输入握手，不需要再改 Scala。每次改动都必须重新生成 output products 并重新看顶层时序，不能仅依据 IP 的 OOC 报告判断。

## Tcl 一键配置（两个版本通用）

先打开已经选好器件的 Vivado RTL 工程，再在 Tcl Console 执行：

```tcl
source D:/Develop/CPU/Archive/mycpu/src/main/scala/mycpu/create_div_gen_0.tcl
```

脚本会：

1. 检查当前版本（目标为 2025.1 或 2026.1）。
2. 从本机 IP Catalog 解析 `*:ip:div_gen:5.1`，兼容 `xilinx.com`/`amd.com` vendor 名称。
3. 创建或重配名为 `div_gen_0` 的 IP。
4. 生成所有 output products，并创建 `div_gen_0_synth_1` OOC run。

然后运行 IP OOC 综合：

```tcl
launch_runs div_gen_0_synth_1
wait_on_run div_gen_0_synth_1
open_run div_gen_0_synth_1
report_timing_summary
report_utilization
```

最后重新综合 CPU 顶层，并确认最差路径已经不再包含普通 RTL 的 `$div`、`$mod`、`/` 或 `%` 运算单元。

## Vivado GUI 配置

Vivado 2025.1 与 2026.1 的操作流程相同：

1. 在 `Flow Navigator -> IP Catalog` 搜索 `Divider Generator`。
2. 双击 `Divider Generator 5.1`，Component Name 填 `div_gen_0`。
3. 按上表设置所有选项；重点确认 `Radix2`、`Remainder`、`Unsigned`、`Blocking`、`ARESETn`。
4. 点击 OK 后选择 `Generate Output Products`，建议使用默认 OOC synthesis。
5. 在 `IP Sources` 中确认实例端口包含两个输入 `tready` 和 `aresetn`，输出宽度为 64 位。

2026.1 如果打开由 2025.1 创建的 XCI 后显示 IP 状态变化，先执行 `report_ip_status`。只有报告要求升级时才对这个 IP 执行 `upgrade_ip [get_ips div_gen_0]`，随后重新生成 output products；不要直接复用旧版本产生的 DCP。更稳妥的做法是每个 Vivado 版本都从本仓库 Tcl 脚本重新创建 XCI/output products。

## 仿真注意事项

Scala elaboration 只产生 `div_gen_0` 黑盒实例，不再提供行为模型。Vivado/xsim 仿真必须把 XCI 及其 simulation output products 加入仿真 fileset。若第三方仿真器只读取 `generated/filelist.f` 而未加载 Xilinx IP 仿真库，会报告找不到 `div_gen_0`，这是预期的集成错误，不能用旧的组合 `/`、`%` 文件回填。

