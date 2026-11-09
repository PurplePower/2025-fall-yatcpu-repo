# lab4 cloud_pynq

目标平台为 cg_fpga 云端 PYNQ-Z2，器件 `xc7z020clg400-1`。
CPU 保留 lab4 的 AXI4Lite 取指与数据访存，总线访问同步读 BRAM，容量 32 KiB。

## 时钟与启动

- 平台 `clk_100M`：100 MHz，经 `clk_wiz` 输出 40 MHz。
- CPU、AXI、BRAM、ROMLoader 和全部外设直接运行在 40 MHz，不做 CPU 分频。
- MTime 对 40 MHz 时钟分频，保持 1 MHz 时间基准。
- UART：9600 baud、8-N-1；`cg_fpga.tx_0` 接 CPU RX，CPU TX 接 `cg_fpga.rx_0`。
- 复位异步置位、各时钟域同步释放；UART TX 在复位期间保持空闲高电平。
- AXI/BRAM 域另加一拍同步复位寄存，避免异步复位同步器直接驱动 BRAM 控制端。
- ROMLoader 经 AXI 写入 RAM，等待最后一次写响应结束后释放 CPU 复位。
- 时钟 IP 提供 40 MHz（25 ns）约束；`clocks.xdc` 只保留复位同步器属性。
- Timer 固件参数按 40 MHz 配置，歌词 10 ms tick 与显示 1 s 动画保持原来的实际间隔。

## 地址映射

| 地址 | 设备 | 说明 |
|---|---|---|
| `0x00000000` | RAM | 32 KiB；程序从 `0x1000` 开始 |
| `0x40000000` | UART | `+0x0c` 接收、`+0x10` 发送、`+0x14` 状态 |
| `0x60000000` | 数码管 | 8 个 32 位寄存器，低有效段码 |
| `0x80000000` | Timer | `+4` limit、`+8` enabled；周期为 limit + 1 |
| `0xa0000000` | LED 矩阵 | 64 个 16 位行数据，硬件取反输出 |
| `0xc0000000` | MTime | `+0` 低 32 位、`+4` 高 32 位；1 tick = 1 µs |

UART 状态 bit0 是 TX ready，bit1 是 RX valid。读取接收寄存器才清除接收状态。
AXI 外设适配器将保持多拍的写请求转换为单次操作，MTime 读响应为稳定快照。
音频与视频输出保持关闭；本平台未接入 VRAM。

## 填空与 C 程序

仓库保留学生 AXI 填空框架。功能测试、RTL 和比特流构建需要先完成实验。
助教可使用本地 `misc/lab4_ans_basic.yaml` 与填充器；该答案不纳入 Git。
在 lab4 目录运行：

```powershell
python -X utf8 ../misc/autoans.py ../misc/lab4_ans_basic.yaml -b .
cmake -S csrc -B csrc/build_cloud_pynq -G "MinGW Makefiles" -DCMAKE_TOOLCHAIN_FILE="$PWD/csrc/toolchain.cmake"
cmake --build csrc/build_cloud_pynq --target pi say_goodbye --parallel
```

Linux 上省略 `-G "MinGW Makefiles"`，或使用 `csrc/build.sh pi say_goodbye`。
Windows 也可使用 `csrc/build.bat pi say_goodbye`。工具链为
`riscv64-unknown-elf-gcc`，ISA 为 `rv32i_zicsr`，ABI 为 `ilp32`。
固件输出到 `src/main/resources/*.asmbin`。

- `pi`：默认计算 200 位，输出 20 位小数，使用软件乘除与 MTime 计时。
- `say_goodbye`：每秒开始一行，播放两行后停止；`s` 暂停、`p` 继续、`r` 重播。
- `led_control_demo`：显示数字 1～8 和 RISC，每秒旋转样式，UART 中断单字节回显。

## 生成 RTL 与比特流

在 lab4 目录：

```powershell
sbt "runMain board.cloud_pynq.VerilogGenerator pi"
cd vivado/cloud_pynq
vivado -mode batch -source generate_bitstream.tcl
```

默认使用 Vivado 2022.1 和仓库的 `cg_fpga:1.4`。
如安装目录不在 PATH，可使用 `E:/Xilinx/Vivado/2022.1/bin/vivado.bat`。
生成的比特流位于
`cloud_pynq/cloud_pynq.runs/impl_1/design_1_wrapper.bit`。
最终时序、DRC、利用率、时钟报告和 routed checkpoint 位于工程的 `reports/`。
生成脚本会检查 setup/hold，必要时执行物理 hold 修复；检查失败时返回错误。

可用环境变量设置独立工程与 RTL，避免两个程序相互覆盖：

```powershell
# 仍在 lab4 时，生成两套独立 RTL。
sbt "runMain board.cloud_pynq.VerilogGenerator pi target/pi-verilog" "runMain board.cloud_pynq.VerilogGenerator say_goodbye target/goodbye-verilog"
$env:CG_FPGA_TOP_V = "$PWD/target/pi-verilog/Top.v"
$env:CG_FPGA_PROJECT_DIR = "cloud_pynq_pi"
cd vivado/cloud_pynq
vivado -mode batch -source generate_bitstream.tcl
```

`CG_FPGA_TOP_V` 使用绝对路径。构建 say_goodbye 时改为相应的 RTL 路径和工程名。
已保留 `open_project_2020.tcl` / `generate_bitstream_2020.tcl` 入口，选择
`cg_fpga:1.6.3`；本次交付使用 2022.1。
也可从 lab4 使用 `make bitstream-cloud_pynq PROGRAM=pi`。

## 验证

```bash
sbt test
make native-cloud-test
```

原生仿真需要 Bash、Verilator、GNU C++ 和 make。Windows 使用 MSYS2 / MinGW64。
`native-cloud-test` 使用三个正式 ROM，生成隔离的 `target/*-verilog`，并验证：

- π 数字、200 位工作量、MTime 和 cycle CSR 比例。
- 歌词内容、每秒开始一行、播放停止、暂停/继续、无效按键和重播。
- 8 位数码管、64 行 LED 矩阵、Timer 动画、UART 中断回显和复位重新装载。

日志位于 `target/*-native.log`，不会生成大型波形文件。

## 本次交付

比特流与最终验收结果见 [lab4/updates.md](../../updates.md)。每个程序的
`bitstreams/<程序名>/` 包含 `.bit`、固件、构建清单、SHA-256、源码快照、
功能仿真记录、时序与 DRC 报告和最终布线 checkpoint。
这些生成物保留在本地并由 Git 忽略。云平台实际烧录尚未验证。
