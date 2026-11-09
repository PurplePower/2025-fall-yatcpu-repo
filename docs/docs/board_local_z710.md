Writen By: [Tokisakix](https://github.com/Tokisakix)

# 如何使用一键烧板脚本

> 此烧板脚本对任意方式配置的开发环境都有效，因为 vivado 的烧板跟开发环境是**相互独立**的，如果你使用 Dev Container 或 WSL 配置环境，你需要回到 Windows 下打开 PowerShell 进行烧板。
> **此部分内容借鉴自 [YatCPU 的烧板验证](https://yatcpu.sysu.tech/getting-started/program-device/)**

原则上你可以在任意一个 lab 文件夹中使用下方的脚本进行烧板，下方的演示路径为 <code>2023-fall-yatcpu-repo/mini-yatcpu/</code>

**生成 Verilog 文件**

下面的教程以 ZYBO-Z710 开发板为例，其他开发板可以根据开发板名称自行替换。

根据开发板型号，在命令行输入对应型号的运行指令，运行相应目录的 Top.scala 文件，生成的结果位于 verilog/开发板名称 目录下的 Top.v，下方是参考的代码：

```
sbt "runMain board.z710.VerilogGenerator"
```

Lab 1–4 和 mini-yatcpu 中所有已实现的 FPGA 板级生成器统一接受两个位置参数：

```text
sbt "runMain board.<板子包名>.VerilogGenerator [程序名[.asmbin]] [输出目录]"
```

- 第一个参数选择烧录到 FPGA 的二进制程序，接受 `say_goodbye` 或
  `say_goodbye.asmbin`；生成前应先编译对应固件，将其放到该 Lab 的
  `src/main/resources/`。生成器只选择固件，不编译 C 源码。
- 第二个参数可选，指定生成 `Top.v` 等 RTL 文件的目录，相对路径以当前 Lab
  为基准。不传时使用 `verilog/<板子目录名>`。
- 不传参数时保留各板原来的默认程序。参数多于两个、程序名为空或输出目录为空
  时会报错。
- `z710v1.3` 的 Scala 包名为 `board.z710v1_3`，目录名仍为 `z710v1.3`。

例如，在相应 Lab 目录运行：

```powershell
sbt "runMain board.z710.VerilogGenerator hello target/z710-hello"
sbt "runMain board.z710v1_3.VerilogGenerator say_goodbye.asmbin target/z710v13-goodbye"
sbt "runMain board.cloud_pynq.VerilogGenerator led_control_demo target/cloud-led"
```

也可以通过 Makefile 传入 `PROGRAM` 与 `OUTPUT_DIR`：

```sh
make z710 PROGRAM=hello OUTPUT_DIR=target/z710-hello
make cloud_pynq PROGRAM=say_goodbye OUTPUT_DIR=target/cloud-goodbye
```

cloud_pynq 的 Make 目标会先构建所选 CMake 固件目标；原来只生成 RTL 的其他板级
目标仍需预先准备固件。Vivado 脚本默认读取原来的 `verilog/<板子目录名>/Top.v`，
自定义 RTL 输出目录后，需要让相应 Vivado 工程读取该目录，或在生成比特流时
使用默认目录。

默认程序如下（均省略 `.asmbin` 后缀）：

| 板子 | Lab 1 | Lab 2 | Lab 3 | Lab 4 | mini-yatcpu |
|---|---|---|---|---|---|
| basys3 | tetris | tetris | tetris | 未实现 | tetris |
| pynq | hello | hello | tetris | 未实现 | litenes |
| z710 | say_goodbye | say_goodbye | say_goodbye | say_goodbye | say_goodbye |
| z710v1.3 | say_goodbye | say_goodbye | say_goodbye | say_goodbye | 未实现 |
| cloud_pynq | led_control_demo | led_control_demo | led_control_demo | led_control_demo | 未实现 |

Lab 2、Lab 3 的 `board.z710v1_3.PiVerilogGenerator` 保留为兼容入口，默认程序为
`pi`，也接受相同的两个位置参数。课程填空应在生成实际 CPU RTL 前完成。
`board.verilator.Top` 的指令和内存由 C++ 仿真程序在运行时提供，不包含烧录 ROM，
继续使用原有仿真入口和运行时程序参数。

你也可以在配置好环境的 IDEA 中打开 src/main/scala/board/z710/Top.scala，点击 object VerilogGenerator extends App 一行左边的绿色三角形运行即可。

**生成比特流二进制文件**

执行下述指令，可以根据 verilog/z710/Top.v 生成二进制文件 vivado/z710/riscv-z710/riscv-z710.runs/impl_1/Top.bit。

假设你的 Vivado 安装目录是 ~/Xilinx(其他目录自行修改):

```
cd vivado/z710
~/Xilinx/Vivado/2020.1/bin/vivado -mode batch -source ./generate_bitstream.tcl
```

**烧板**

执行下述指令，可以将二进制文件 vivado/z710/riscv-z710/riscv-z710.runs/impl_1/Top.bit 烧录到跟设备连接的开发板上。

假设你的 Vivado 安装目录是 ~/Xilinx(其他目录自行修改):

```
cd vivado/z710
~/Xilinx/Vivado/2020.1/bin/vivado -mode batch -source ./program_device.tcl
```

**如果烧板无误，你会看到黄灯亮起**

![image](asset/z710.jpg)

后续将上述 program_device.tcl 换成 generate_and_program.tcl 可以将生成比特流和烧板在一个脚本中完成。
