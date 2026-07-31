# Vivado Multiplier Generator

`MDU.scala` 只声明名为 `multiplier` 的黑盒，不携带、复制或内联任何
NOP-Core XCI。综合、实现和 XSim 使用的定义必须由当前 Vivado 版本从内置
`Multiplier Generator 12.0` 生成。

接口为：

```text
CLK
A[31:0]
B[31:0]
P[63:0]
```

配置为 32 位无符号乘 32 位无符号、64 位结果、DSP 实现、Speed 优化、
2 级流水、吞吐率每拍一条，不启用 CE 和 SCLR。

在仓库根目录执行：

```powershell
& E:\AMDMesignTools\2026.1\Vivado\bin\vivado.bat `
  -mode batch `
  -source .\scripts\create_multiplier_ip.tcl `
  -tclargs D:\Develop\chiplab\IP\myCPU\xilinx_ip
```

生成物是：

```text
D:\Develop\chiplab\IP\myCPU\xilinx_ip\multiplier\multiplier.xci
```

工程中只加入该 XCI，不要递归加入它下面的 `hdl`、`sim`、`synth` 或
`ip_user_files`。`multiplier_sim.sv` 仅用于不支持 XCI 的独立 RTL 仿真；
Vivado 工程使用 XCI 时不能再加入这个行为模型。

完整的乘除法 IP 生成、接入和验证流程见仓库根目录
`VIVADO_ARITHMETIC_IP_GUIDE.md`。
