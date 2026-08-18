# MDU 乘法 IP 配置说明

## 已完成/需要的 RTL 修改

`MDU.scala` 中的 `mult_gen_0` 是 Vivado Multiplier Generator v12.0 的纯黑盒声明，
不再内联任何 `*` 组合乘法表达式。`Multiplier` wrapper 负责：

- 统一 MUL.W / MULH.W / MULH.WU：对操作数做符号/零扩展，始终送一个 33x33 有符号
  乘法进 IP，再按 `highWord` 选高/低 32 位（低 64 位与对应 32x32 结果一致）。
- 只用一比特 `productValid` 记录"积已就绪"，高/低位选择放在 IP 输出之后，因此 DSP
  级联不会延伸到 EX 结果 mux。
- `flush`（wbFlush / M1 late fault）只清 `productValid`；IP 内部的积寄存器在下一次
  enable 时被覆盖，无需 SCLR。
- `done` 保持到后端 `consume`，保证 EX 包被下游反压压住时结果不丢。

## 必须先移除旧行为级模块

Vivado 工程和任何手写 filelist 中都不能再包含带 `*` 的乘法实现。尤其检查：

- 任何旧 `mult_gen_0.v/.sv` 行为模型（只能保留 `src/test/resources/mult_gen_0_sim.sv`
  这个仿真替身，且不得进入 synthesis fileset）。

如果旧行为级乘法与 XCI 同时存在，会出现 `mult_gen_0` 重定义。

## 推荐配置（Multiplier Generator v12.0）

| 选项 | 值 | 原因 |
| --- | --- | --- |
| Component Name | `mult_gen_0` | 必须与 Chisel 黑盒模块名完全一致 |
| Multiplier Type | Parallel Multiplier | 单拍组合乘法 |
| Input Options | Signed | wrapper 统一做符号/零扩展，IP 恒为有符号 33x33 |
| Port A Width | 33 | 32 位源 + 1 位符号扩展 |
| Port B Width | 33 | 同上 |
| Output Product Range | Full (66-bit) | 需要 [63:32] 与 [31:0] 两段 |
| Pipeline Stages | 1 | 积寄存到 DSP 内部（MREG/PREG），不进 fabric FF |
| Clock Enable | None | 当前没有 CE 端口 |
| Synchronous Clear | None | wrapper 的 productValid 已处理 flush |
| Optimization Goal | Speed | 频率优先 |

`Pipeline Stages = 1` 会把 66 位积寄存在 DSP 内部寄存器里，等价于把之前 fabric 里的
`productReg` 搬进 DSP，省掉 66 个 fabric FF，并让 DSP 组合积只走到内部 MREG 而不是
拉到 fabric FF 的 D 端。MUL 仍是 2 拍（1 拍锁积 + 1 拍消费），延迟不变。

## Vivado GUI 配置

1. `Flow Navigator -> IP Catalog` 搜索 `Multiplier`。
2. 双击 `Multiplier 12.0`，Component Name 填 `mult_gen_0`。
3. 按上表设置；重点确认 Signed、33x33、Pipeline Stages = 1、Full output range。
4. OK 后 `Generate Output Products`，默认 OOC synthesis。
5. 在 `IP Sources` 里确认端口为 `CLK / A[32:0] / B[32:0] / P[65:0]`。

## 仿真注意事项

Scala elaboration 只产生 `mult_gen_0` 黑盒实例。Vivado/xsim 仿真必须把 XCI 及
simulation output products 加入仿真 fileset。Verilator 等软件仿真器用仓库
`src/test/resources/mult_gen_0_sim.sv`（端口等价、1 拍延迟的行为替身），它不在 main
source set，受控 RTL 导出脚本也不会写入 manifest，不得加入 Vivado synthesis fileset。

chiplab 的 Verilator 流程在 `sims/verilator/run_prog/Makefile` 里通过
`VERILATOR_SIM_MODEL_DIR` 把该模型加入编译；不要把它复制进 `IP/myCPU`，否则会被
`$(wildcard ${MYCPU_SRC}/*.sv)` 扫进 Vivado 源。
