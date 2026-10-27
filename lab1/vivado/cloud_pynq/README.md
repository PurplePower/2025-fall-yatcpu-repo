# cloud_pynq — 云 FPGA 平台（cg_fpga）烧录工程

本工程把 lab1 的 RISC-V CPU 作为 BD 模块接到 `cg_fpga` 云平台 IP，
用于希冀（educg）云 FPGA 平台的网页展示。

## 前置条件

1. Vivado **2022.1**。
2. RISC-V GCC 工具链（`riscv64-unknown-elf-gcc`、`objcopy`）在 `PATH` 中。
3. 已安装 `online-fpga/cg-fpga-vivado2022.1/board_files/pynq-z2` board files。
4. 已把 `online-fpga/cg-fpga-vivado2022.1/ip_repo` 加入 Vivado IP Repository。
5. lab1 的 CPU TODO 已完成（`InstructionFetch`、`InstructionDecode`、`Execute`、`CPU`）。
6. 已生成 `verilog/cloud_pynq/Top.v`。

> 注意：仓库中的 lab1 默认是学生填空版。若只是验证平台，可先用
> `misc/lab1_ans.yaml` 本地填充核心；提交到仓库时保持 lab1 骨架不变。
> 生成的 `verilog/cloud_pynq/Top.v` 和 Vivado 工程目录已加入 `.gitignore`，
> 不随仓库提交。

## 生成程序与 Verilog

```bash
cd lab1
make cloud-pynq-program   # 生成 src/main/resources/say_goodbye.asmbin
make cloud_pynq           # 生成 verilog/cloud_pynq/Top.v

# 当前 cloud_pynq 默认运行 say_goodbye。
# 如需切回数码管 demo，可重新编译并修改 Top 的 binaryFilename：
make program PROGRAM=seg_demo
```

## 生成 bitstream

```bash
cd lab1/vivado/cloud_pynq
vivado -mode batch -source generate_bitstream.tcl
```

生成的 bitstream：

```text
lab1/vivado/cloud_pynq/cloud_pynq/cloud_pynq.runs/impl_1/design_1_wrapper.bit
```

在云平台网页的 FPGA 烧录入口上传该 `.bit` 文件，等待烧录完成后按 RST。

## 连接关系

`Top.scala` 是 `RawModule`，显式声明 `clock` / `reset_n`，BD 直接例化 `Top`，
不再有手写的 `cloud_pynq_top.v` / `clock_control.v`。

```text
cg_fpga_0/clk_100M -> clk_wiz_0/clk_in1
clk_wiz_0/clk_50m  -> Top_0/clock     -> Memory/UART/Seg = 50 MHz
cg_fpga_0/btn_rst  -> reset logic     -> Top_0/reset_n
cg_fpga_0/tx_0     -> Top_0/uart_rx   -> CPU UART RX
cg_fpga_0/rx_0     <- Top_0/uart_tx   -> CPU UART TX
cg_fpga_0/btn/sw*  -> Top_0
Top_0/gpio_led/seg/ledm/audio -> cg_fpga_0
```

CPU 在 `Top` 内部再 4 分频：**CPU = 12.5 MHz，Memory = 50 MHz**。
`clk_wiz_0` 的 `locked` 会和 `btn_rst` 一起参与 Top 复位释放。
`ClockRatioCheck` 会在仿真中检查 CPU 至少比 Memory 慢 2 倍。

UART 是 **TX/RX 交叉**：`tx_0` 是内部 Uartlite 的 TX 输出，`rx_0` 是
Uartlite 的 RX 输入；网页/PS 位于 Uartlite 的 AXI 侧。

## MMIO

| deviceSelect | 基地址 | 设备 |
|---:|---|---|
| 0 | `0x0000_0000` | 32 KiB RAM |
| 2 | `0x4000_0000` | UART 9600-8-N-1 |
| 3 | `0x6000_0000` | 8 位数码管（SegDisplay） |

UART 寄存器：

```text
+0x04  波特率（只读）
+0x08  中断
+0x0C  接收数据（读清除 rx_valid）
+0x10  发送数据
+0x14  状态：bit0 = TX ready，bit1 = RX valid
```

`say_goodbye.c` 通过轮询 `UART_STAT` 的 bit0 做流控，不再依赖固定延时。
MMIO 定义统一在 `csrc/mmio.h`。

> 注意：`cg_fpga` 内部的 AXI Uartlite RX FIFO 只有 16 字节。CPU 侧流控只能
> 保证自己的 1 字节发送缓冲不溢出，不能保证 PS/网页侧及时读走 Uartlite。
> 如果网页串口轮询较慢，9600 下仍可能丢字符；`say_goodbye.c` 因此加入了
> `uart_pace()` 节流。若网页端能连续读取，可去掉该延时以提高速度。

数码管寄存器（32 位写）：

```text
SEG_BASE + digit * 4
bit[7:0] = 段码（平台为低有效，0 点亮；C 程序负责把高有效表取反）
bit[8]   = 显示使能
```

当前默认程序 `say_goodbye.c` 通过 UART 输出字符串；如需数码管演示，
可用 `make program PROGRAM=seg_demo` 生成 `seg_demo.asmbin`，再把
`Top.scala` 的 `binaryFilename` 改回 `seg_demo.asmbin` 并重新生成 bitstream。

## 说明

- LED 矩阵、音频、视频接口已在 Top 中预留；当前版本输出关闭。
- 视频的 `vid_io_in_clk` 目前临时接 `clk_100M`，后续应改为 74.25 MHz。
- 程序随 bitstream 烧录，修改 C 程序后需要重新生成 asmbin、Verilog 和 bitstream。
