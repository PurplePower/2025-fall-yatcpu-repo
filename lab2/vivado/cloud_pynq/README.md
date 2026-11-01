# lab2 cloud_pynq：Timer 中断动画

此工程将 lab2 的 RV32I + Zicsr 教学 CPU 接入 cg_fpga 云平台。默认程序
`led_control_demo` 通过 Timer 中断每秒切换一次显示，四秒循环。

| 启动后的样式 | 四块 LED 矩阵（从左到右） | 八位数码管 |
|---|---|---|
| 0 | R I S C，正像 | 1 2 3 4 5 6 7 8 |
| 1 | I S C R，正像 | 2 3 4 5 6 7 8 9 |
| 2 | S C R I，反像 | 3 4 5 6 7 8 9 A |
| 3 | C R I S，反像 | 4 5 6 7 8 9 A B |

Timer ISR 只递增 `seconds`，主循环根据它更新显示。变化不是忙等延时产生的。
网页 UART 输入单个字符后，UART ISR 读取字符，主循环回显一次；串口设置为
9600、8 数据位、无校验、1 停止位。GPIO LED 显示取反后的 PC。

## 已生成的比特流

2026-11-01 使用 Vivado 2020.1、cg_fpga 1.6.3、`xc7z020clg400-1` 生成：

- [cloud_pynq_2020_led_control_demo.bit](bitstreams/cloud_pynq_2020_led_control_demo.bit)，4,045,676 字节，内置正式一秒周期程序。
- [SHA256SUMS.txt](bitstreams/SHA256SUMS.txt)及[构建清单](bitstreams/build_manifest.json)。
- [时序报告](bitstreams/reports/timing_summary.rpt)：WNS +1.055 ns，WHS +0.011 ns，TNS/THS 0，无 setup/hold/pulse-width 违例。
- [DRC](bitstreams/reports/drc.rpt)：0 errors，1 项平台 IP 无可布线负载 warning，3 项 BRAM WRITE_FIRST advisory；RAM 异步复位告警已消除。
- 15 项软件测试、正式 ROM 的 81,000,000 周期仿真及复位重启通过；尚未在云平台实际烧录验证。

SHA-256：`937FDBCC42AB10275C9B79F1B7BD19B31DB331F7F384EA2C63C93E27D793EE30`。
这些生成物被 Git 忽略，源码重新构建方式见下文。

## say_goodbye：UART 定时播放与控制

已于 2026-11-01 使用 Vivado 2020.1 + cg_fpga 1.6.3 生成
[cloud_pynq_2020_say_goodbye.bit](bitstreams/say_goodbye/cloud_pynq_2020_say_goodbye.bit)，
大小 4,045,676 字节。主时钟 40 MHz，CPU/Timer 10 MHz，UART 9600、8-N-1。
最终 WNS +0.708 ns，WHS +0.004 ns，TNS/THS 0，无 setup/hold/pulse-width 违例。
DRC 为 0 errors，1 项平台 IP 无可布线负载 warning，3 项 BRAM WRITE_FIRST advisory。

[校验和](bitstreams/say_goodbye/SHA256SUMS.txt)、
[构建清单](bitstreams/say_goodbye/build_manifest.json)、
[时序报告](bitstreams/say_goodbye/reports/timing_summary.rpt)及
[最终布线 checkpoint](bitstreams/say_goodbye/reports/final_routed.dcp)一并保存。
RTL 与正式固件 UART 仿真使用的版本完全一致；尚未在云平台实际烧录。

`say_goodbye` 使用仓库原有的两行歌词片段，文本集中在 `csrc/say_goodbye.c` 的
`lyrics[]`，每个数组元素为一行并以 `\r\n` 结束；如需更换或补充已提供的文本，修改此数组。

- 启动后自动播放，约一秒后开始第一行，此后每秒开始下一行，播完一次自动停止。
- `s`：暂停，保留当前行及字符位置。已经交给 UART 的字节会完成发送。
- `p`：继续暂停的内容；行内暂停时从下一字符继续，行间暂停时等待一秒再开始下一行。
  正在播放时重复 `p` 不改变进度；播完后 `p` 保持停止，重播使用 `r`。
- `r`：输出一个 CRLF，丢弃旧的未发送内容，从第一行重新播放；播放中、暂停中和播完后均有效。
- 只处理小写单字母命令，不回显命令；其他字符及终端的 CR/LF 被忽略。

云板 Timer 为 10 MHz，`GOODBYE_TIMER_TICKS=100000`，limit 为 99999，每 10 ms
中断一次；每 100 次中断开始一行，并以每 10 ms 一个字节节流发送。ISR 只更新时间或
记录接收字符，主循环处理播放和发送，等待 UART ready 时仍能处理命令。适用现有的
单字符交互；课程 CLINT 的同时中断限制仍然保留。若文本单行超过 100 字节，发送时间会
超过一秒，应缩短行或调整节流参数。其他板子应按其 CPU/Timer 时钟设置该编译宏。

在 `lab2` 目录构建、选择该程序：

```powershell
.\csrc\build.bat say_goodbye
sbt "runMain board.cloud_pynq.VerilogGenerator say_goodbye"
```

修改程序后，继续按下文执行 Vivado 构建以更新比特流。两份交付文件分别命名为
`cloud_pynq_2020_say_goodbye.bit` 和 `cloud_pynq_2020_led_control_demo.bit`，上传时按所需演示选择。

在具备 Verilator、make、g++、sbt 和 RISC-V 工具链的 shell 中，运行
`make native-goodbye-test` 验证正式周期和 UART 控制（Windows 使用 MSYS2 MinGW64）。
该测试解码实际 TX 波形并从 RX 引脚发送命令，使用正式固件及真实的一秒周期；
独立 RTL 和仿真产物在 `target/goodbye-verilog`、`target/goodbye-native` 下。

## 构建环境

- Scala 2.13.10、sbt 1.9.6、Chisel 3.6.0，软件测试使用 Verilator。
- Windows：MSYS2 MinGW64 中的 RISC-V GCC、CMake、mingw32-make 在 PATH 中。
- 首选 Vivado **2020.1**，使用仓库 `online-fpga/cg_fpga_2018_1_6_3`，VLNV
  `educg.net:user:cg_fpga:1.6.3`。
- 另保留 Vivado **2022.1** 入口，使用 `cg-fpga-vivado2022.1/ip_repo` 的
  `cg_fpga:1.4`。两个版本使用独立工程目录，不混用生成缓存。
- Tcl 自动加载仓库内 pynq-z2 board files。
- 仓库保留学生填空框架，运行功能测试或生成 RTL 前需先完成 lab2 填空。本文记录的功能测试和比特流验收基于开发时填入答案的完整 CPU。答案、`.scala.backup`、生成 RTL 和 Vivado 工程不作为学生版源码发布。

## Windows / PowerShell

在 `lab2` 目录运行：

```powershell
# 构建所有程序；包括重新生成原有测试使用的资源。
.\csrc\build.bat
# 重新生成测试专用的快速动画与陷阱寄存器检查程序。
.\csrc\build.bat test_programs
sbt test
sbt "runMain board.cloud_pynq.VerilogGenerator led_control_demo"

Set-Location vivado/cloud_pynq
$env:CG_FPGA_PROJECT_DIR = 'cp2020_lab2'
& 'E:/Xilinx/Vivado/2020.1/bin/vivado.bat' -mode batch -source generate_bitstream_2020.tcl
```

仅构建一个程序可以运行 `.\csrc\build.bat led_control_demo`。Linux 使用
`sh csrc/build.sh` / `sh csrc/build.sh test_programs`；也可用
`make cloud_pynq PROGRAM=led_control_demo`，Makefile 根据 Windows/Linux 选择生成器。

2020.1 默认输出：

```text
cloud_pynq_2020/cloud_pynq_2020.runs/impl_1/design_1_wrapper.bit
cloud_pynq_2020/reports/timing_summary.rpt
cloud_pynq_2020/reports/drc.rpt
cloud_pynq_2020/reports/clocks.rpt
cloud_pynq_2020/reports/check_timing.rpt
```

2022.1 使用 `generate_bitstream.tcl`，工程目录为 `cloud_pynq`。
环境变量 `CG_FPGA_PROJECT_DIR` 可指定其他工程目录。
可选环境变量 `CG_FPGA_INCREMENTAL_DCP` 指定同一设计先前的 routed DCP，以复用布线。
该 DCP 必须保存在工程 runs 目录外，因为重新构建会重置 runs；全新构建无需设置此变量。
上面的命令将输出到 `cp2020_lab2/cp2020_lab2.runs/impl_1/design_1_wrapper.bit`，
对应报告在 `cp2020_lab2/reports`。旧 50 MHz 工程不能直接复用，脚本会检查时钟配置。

修改程序后必须执行 `C/汇编 -> asmbin -> Top.v/asmbin.txt -> synthesis ->
implementation -> bitstream`。只重新运行 implementation 不会更新程序。
构建脚本会检查 setup/hold slack，失败时返回错误；仍需结合 DRC 和未约束路径报告检查平台 IP。
`finalize_bitstream.tcl` 会对残留 hold 违例执行布线后的物理优化，并在时序通过后重新写出
比特流；时钟约束保持不变。若执行了此修复，最终 checkpoint 保存在
`<工程目录>/reports/final_routed.dcp`，最终报告在同一目录。原 `impl_1` 的 routed
checkpoint 保留修复前状态，GUI 复查最终实现时应打开 `final_routed.dcp`。

## 时钟、复位与 MMIO

- cg_fpga 100 MHz 经 clk_wiz 生成 40 MHz，驱动 RAM、ROMLoader、UART、显示扫描。
- 相比 lab1 的 50 MHz 降频，为 lab2 CSR/中断组合路径留出裕量；实现使用 Performance_Explore 策略。
- CPU 由寄存器分频位产生 10 MHz 时钟，Timer 使用相同时钟，避免短脉冲漏采。
- `clocks.xdc` 为该 /4 时钟建立 generated-clock 约束。
- `reset_n` 与 clk_wiz locked 相联；Top 和 CPU 域分别同步释放复位。
- 40 MHz 外设写使能每条 store 只产生一个脉冲；同 CPU 时钟的 Timer 不重复套用该脉冲。
- UART 连接为 `cg_fpga.tx_0 -> CPU RX`，`CPU TX -> cg_fpga.rx_0`。

| 基地址 | 功能 |
|---|---|
| `0x0000_0000` | 32 KiB RAM，程序入口 `0x1000`，栈顶 `0x8000` |
| `0x4000_0000` | UART：`+4` 波特率，`+8` 中断，`+12` RX，`+16` TX，`+20` 状态 |
| `0x6000_0000` | 数码管：每位 4 字节，bit 8 使能，低 8 位为低有效段码 |
| `0x8000_0000` | Timer：`+4` limit，`+8` enabled |
| `0xA000_0000` | 四块 16×16 LED 矩阵，行位图 bit 0 为最左列，软件 1 点亮 |

UART 状态 bit 0 为 TX ready，bit 1 为 RX valid。只有实际消费 RX 的 load 才清除
接收状态，访问其他设备或状态寄存器不会清除。接收缓冲为单字节，不支持无限连续输入。

当前教学 Timer 周期是 `limit + 1` 个 CPU 时钟，因此默认程序设置
`limit = 10_000_000 - 1`。计数器在 disabled 时仍运行，初次使能后的第一拍可能不足
完整一秒，后续事件间隔为一秒。禁用只屏蔽中断，不清零计数器，保持原实验答案语义。

`led_control_demo_fast` 仅用于加速测试，limit 为 19999；烧录时应选择
`led_control_demo`。原生 Verilator 测试直接运行正式程序验证 40,000,000 个主时钟
对应一秒，未通过替换正式程序定时参数来推断实际周期。在具有 Verilator、make、
本机 g++ 的 shell 中，从 lab2 执行 `make native-cloud-test`（Windows 使用 MSYS2
MinGW64 shell）。此项运行 81,000,000 周期，独立于较快的 `sbt test`。

## 验收范围

软件回归覆盖原 lab2 测试、矩阵扫描、UART 帧与接收读确认、Timer 周期/禁用恢复、
trap 寄存器保存、四种样式循环、实际一秒周期和单字符回显。
实体 z710/z710v1.3 也同步了单次 MMIO 写、UART 状态和 Timer 连接；其 Timer 频率为
各板 CPU 时钟（分别 6.25 / 12.5 MHz），软件应按板子设置 limit。

音频和云端视频仍关闭，旧 `hello`/HDMI 程序保留供实体板使用。
CLINT 仍为课程简化实现，Timer 与 UART 同时发生时沿用原中断编码约定；没有新增
完整优先级控制器或在 MIE 屏蔽期间排队的事件机制。

上传最终 `.bit` 后按 RST，观察四秒样式循环、复位回到初始样式、单字符 UART 回显。
实际网页显示、云端 UART FIFO 服务速度和复位行为需要云平台上板确认。
