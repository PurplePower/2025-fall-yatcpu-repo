# lab1 修改总结

本文汇总 lab1 中已经完成的板级支持、外设、MMIO、UART、构建系统等修改，便于同步到其他 lab。

## 1. 新增 cloud_pynq 板级支持

### 新增目录

```text
lab1/src/main/scala/board/cloud_pynq/
lab1/vivado/cloud_pynq/
```

### 核心文件

- `lab1/src/main/scala/board/cloud_pynq/Top.scala`
  - 改为 `RawModule`，显式端口：
    - `clock`
    - `reset_n`（低有效）
  - 复位同步：异步输入、同步释放
  - 平台极性处理：
    - `btn / sw1 / sw2` 低有效
    - `gpio_led / ledm_data / audio` 低有效
    - `seg_data` 低有效，由 C 程序对段码取反
  - 时钟：
    - `clk_wiz`：100 MHz → 50 MHz
    - Memory / UART / SegDisplay / LedMatrixDisplay / ROMLoader：50 MHz
    - CPU：50 MHz / 4 = 12.5 MHz
  - UART 交叉连接：
    - `cg_fpga.tx_0` → CPU `uart_rx`
    - CPU `uart_tx` → `cg_fpga.rx_0`
  - `gpio_led` 显示当前 PC，低有效取反
  - LED 矩阵由 `LedMatrixDisplay` 扫描，音频、视频输出暂时关闭

- `lab1/vivado/cloud_pynq/open_project.tcl`
  - 创建 `cg_fpga` + `Top_0` + `clk_wiz_0`
  - 复位逻辑：`btn_rst` 与 `clk_wiz.locked` 参与 `Top.reset_n`
  - 连接 UART、数码管、LED、开关等

- `lab1/vivado/cloud_pynq/generate_bitstream.tcl`
  - `update_module_reference design_1_Top_0_0`
  - 使用 `Flow_RuntimeOptimized` synthesis / implementation strategy
  - 重新综合、实现、生成 bitstream

- `lab1/vivado/cloud_pynq/README.md`
  - 构建、烧录、MMIO、UART 说明

## 2. 七段数码管支持

### 新增文件

```text
lab1/src/main/scala/peripheral/SegDisplay.scala
lab1/csrc/seg_demo.c
lab1/src/main/resources/seg_demo.asmbin
```

### 功能

- 8 位数码管扫描控制器
- MMIO：

```text
SEG_BASE = 0x6000_0000
地址 = SEG_BASE + digit * 4
bit[7:0] = 段码
bit[8]   = 显示使能
```

- 硬件自动扫描，每位刷新时间默认 `1000 us`
- 输出：
  - `seg_cs[3:0]`
  - `seg_data[7:0]`
- `seg_demo.c`
  - 显示 `1 2 3 4 5 6 7 8`
  - 平台 `seg_data` 为低有效，所以程序对常见高有效段码取反

## 3. UART 改进

### 修改文件

```text
lab1/src/main/scala/peripheral/UART.scala
lab1/csrc/say_goodbye.c
lab1/src/main/scala/board/z710/Top.scala
lab1/src/main/scala/board/z710v1.3/Top.scala
lab1/src/main/scala/board/cloud_pynq/Top.scala
```

### 主要修复

1. **8-N-1 帧格式**
   - `Tx` 移位寄存器改为 10 位
   - 修复 1 停止位改动后多移出一个 `0`，导致接收端解出 `0xFF` 的问题
   - 原问题表现为只收到 `"Nÿ"`（`4E FF`）

2. **发送流控**
   - `BufferedTx` 暴露 `ready`，表示单字节 Buffer 为空
   - `Uart` 新增状态寄存器：

```text
UART_STAT = UART_BASE + 0x14
bit0 = TX ready（Buffer 空）
bit1 = RX valid（粘滞，读 0x0C 清 0）
```

3. **`say_goodbye.c` 改为轮询发送**
   - 不再依赖 `waste_some_time`
   - 使用 `UART_STAT` 的 bit0 等待发送缓冲空闲
   - 换行改为 `\r\n`
   - 增加 `uart_pace()` 节流，当前为 `10000` 次循环
     - 用于规避云平台 AXI Uartlite RX FIFO 只有 16 字节、网页/PS 轮询较慢导致的丢字

4. **MMIO 写脉冲单周期化**
   - CPU 是 Memory 时钟的 1/4
   - `write_enable` 会保持 4 个 Memory 周期
   - 在 board Top 中用 `CPU_tick` 限定 UART / Seg / Memory 写，避免重复写

5. **各板 UART 参数**
   - z710：115200
   - z710v1.3：115200
   - cloud_pynq：9600

## 4. 统一 MMIO 地址

### 修改文件

```text
lab1/csrc/mmio.h
```

### 删除文件

```text
lab1/csrc/mmio_cloud_pynq.h
```

统一后的映射：

| `deviceSelect` | 地址 | 设备 |
|---:|---|---|
| 0 | `0x0000_0000` | 32 KiB RAM |
| 1 | `0x2000_0000` | CharacterDisplay / VRAM |
| 2 | `0x4000_0000` | UART |
| 3 | `0x6000_0000` | 8 位数码管 |
| 4 | `0x8000_0000` | Timer（预留） |
| 5 | `0xA000_0000` | 四块 16x16 LED 矩阵 |

`deviceSelect = addr[31:29]`。

UART 寄存器：

```text
+0x04  波特率（只读）
+0x08  中断
+0x0C  接收
+0x10  发送
+0x14  状态：bit0 TX ready，bit1 RX valid
```

头文件还包含：

- `VRAM`
- `SEG_REG` / `seg_set` / `seg_clear`
- `LEDM_REG` / `ledm_set_row`
- `TIMER_*`
- MMU 程序使用的 `VA_*`
- 各板能力矩阵注释

## 5. 统一 linker script 和 startup

### 修改文件

```text
lab1/csrc/link.lds
lab1/csrc/init.S
```

### 删除文件

```text
lab1/csrc/link_cloud_pynq.lds
lab1/csrc/start_cloud_pynq.S
```

统一布局：

```text
0x1000  .text
0x2000  .data（页对齐）
0x5000  paging 页表
0x6000  .bss
0x8000  初始栈顶
```

`init.S`：

- `sp = __stack_top`
- 启动时清零 `.bss`

适用于所有 32 KiB BRAM、入口 `0x1000` 的 lab1 板子。  
如果其他 lab 的 BRAM 大小、入口地址或页表布局不同，需要相应修改。

## 6. 时钟比例检查

### 新增文件

```text
lab1/src/main/scala/peripheral/ClockRatioCheck.scala
```

- 仿真期检查 CPU 时钟至少比 Memory 时钟慢 `minRatio` 倍
- cloud_pynq 使用 `minRatio = 2`，实际为 4 分频

### 修改文件

```text
lab1/src/main/scala/peripheral/Memory.scala
```

- 增加同步读 BRAM 有一拍延迟的注释
- 说明 CPU 必须慢于 Memory

## 7. 构建系统统一

### 修改文件

```text
lab1/csrc/CMakeLists.txt
lab1/csrc/toolchain.cmake
lab1/csrc/build.bat
lab1/csrc/build.sh
lab1/Makefile
lab1/.gitignore
```

### 删除文件

```text
lab1/csrc/build_cloud_pynq.sh
```

### 当前方式

- `CMakeLists.txt` 统一构建所有程序
- `say_goodbye` 单独使用 `-O2`
- 其他程序保持 `-O0`
- `cloud_pynq` 是 CMake convenience target
- `toolchain.cmake` 使用 `riscv64-unknown-elf-gcc/g++/ar/objcopy`
- `build.bat` 用于 Windows + MSYS2，使用 `MinGW Makefiles`
- `build.sh` 用于原生 Linux
- `Makefile` 的 `cloud-pynq-program`、`program`、`z710`、`z710v1.3` 都改为调用 CMake

### asmbin 的读取阶段

- Chisel elaboration 阶段读取 `.asmbin`
- 生成 `verilog/<program>.asmbin.txt`
- Vivado synthesis 阶段通过 `$readmemh` 读取 `.txt`
- implementation / write_bitstream 不再读取 asmbin

因此修改 C 程序后必须：

```text
重新生成 asmbin
→ 重新生成 Top.v
→ Vivado 重新综合
```

## 8. 其他板级 Top 修改

### z710 / z710v1.3

修改：

```text
lab1/src/main/scala/board/z710/Top.scala
lab1/src/main/scala/board/z710v1.3/Top.scala
```

内容：

- 移除 `Dummy`
- 显式连接 Memory / UART 总线
- UART 写使用 `CPU_tick` 限定
- 读数据通过 `deviceSelect` 选择 UART 或 Memory
- 保留 115200 波特率

### 文档

修改：

```text
online-fpga/README.md
online-fpga/cg-fpga-vivado2022.1/README.md
online-fpga/cg-fpga-vivado2022.1/CHANGELOG.md
online-fpga/cg-fpga-vivado2022.1/MANIFEST.md
online-fpga/cg-fpga-vivado2022.1/SHA256SUMS.txt
online-fpga/cg-fpga-vivado2022.1/docs/cg_fpga_port_map.md
online-fpga/cg-fpga-vivado2022.1/docs/riscv_cpu_integration.md
```

主要是修正 cg_fpga UART RX/TX 方向说明：

- `tx_0` 是 Uartlite TX 输出 → CPU RX
- `rx_0` 是 Uartlite RX 输入 ← CPU TX

## 9. 修改文件夹清单

| 路径 | 内容 | 同步建议 |
|---|---|---|
| `lab1/src/main/scala/peripheral/` | `SegDisplay.scala`、`LedMatrixDisplay.scala`、`ClockRatioCheck.scala`、`UART.scala`、`Memory.scala` | 通用，可直接复制；UART 参数按板子调整 |
| `lab1/src/main/scala/board/cloud_pynq/` | cloud_pynq 顶层 | 仅适用于 cg_fpga 云平台，需按目标板改 |
| `lab1/src/main/scala/board/z710*/` | UART/总线连接方式 | 作为其他板 Top 的参考 |
| `lab1/csrc/mmio.h` | 统一 MMIO | 通用，合并板级地址即可 |
| `lab1/csrc/link.lds` | 统一链接布局 | 通用，按 BRAM 大小/入口调整 |
| `lab1/csrc/init.S` | 栈、bss 清零 | 通用，依赖 linker symbol |
| `lab1/csrc/say_goodbye.c` | UART 轮询、CRLF、pace | 通用，pace 按平台 FIFO/轮询调整 |
| `lab1/csrc/led_control_demo.c` | 数码管 + LED 矩阵合并 demo | cloud_pynq 专用 |
| `lab1/csrc/CMakeLists.txt` | 统一构建 | 通用，改程序列表 |
| `lab1/csrc/toolchain.cmake` | RISC-V GCC 工具链 | 通用，按工具链前缀调整 |
| `lab1/csrc/build.bat` / `build.sh` | Windows/Linux 构建入口 | 通用 |
| `lab1/vivado/cloud_pynq/` | cloud_pynq Vivado 工程脚本 | 仅 cloud_pynq |
| `lab1/Makefile` | 构建入口 | 通用，按 lab 调整 target |
| `lab1/.gitignore` | 忽略生成物 | 合并即可 |
| `online-fpga/.../docs/` | UART 方向说明 | 同步文档 |

### 已删除的旧文件

```text
lab1/csrc/link_cloud_pynq.lds
lab1/csrc/mmio_cloud_pynq.h
lab1/csrc/start_cloud_pynq.S
lab1/csrc/build_cloud_pynq.sh
```

同步到其他 lab 时不要再复制这些文件。

## 10. 同步到其他 lab 的建议顺序

1. 复制通用外设：
   - `SegDisplay.scala`
   - `ClockRatioCheck.scala`
   - 新版 `UART.scala`

2. 合并 `mmio.h`：
   - 保留其他 lab 的设备地址
   - 加入 UART `0x14` 状态寄存器
   - 加入 SEG、VRAM、Timer、`VA_*`

3. 统一 `link.lds` / `init.S`：
   - 确认 BRAM 大小、入口地址、栈顶
   - 确认 `.bss` 和页表不冲突

4. 修改各板 `Top.scala`：
   - MMIO 写加 `CPU_tick`
   - UART 使用 `UART_STAT` 流控
   - 数码管接 `SegDisplay`
   - 复位同步和平台极性

5. 更新构建：
   - `CMakeLists.txt`
   - `toolchain.cmake`
   - `build.bat` / `build.sh`
   - `Makefile`

6. 重新生成：

```text
asmbin → Top.v → synthesis → implementation → bitstream
```

7. 上板验证：
   - UART 完整输出
   - 数码管段码/位选
   - RST、LED、开关

## 11. 当前 Git 状态

- 已提交：
  - `3648644 Implement cloud_pynq board with SegDisplay and 50MHz memory clock`
  - `8ed927c Correct cg_fpga UART RX/TX direction documentation`
- 当前还有一批**未提交**修改：
  - 统一 MMIO / linker / startup
  - UART 流控、8-N-1、`UART_STAT`
  - cloud_pynq 切到 `say_goodbye`
  - CMake / build.bat / build.sh 重构
  - `Flow_RuntimeOptimized` Vivado strategy
  - `say_goodbye` pace 调整
- `main` 未改动，`dev` 尚未 push。

## 12. LED 矩阵支持

新增 `LedMatrixDisplay.scala`、`led_matrix_demo.c` 和对应 ChiselTest。
控制器使用 64 行 x 16 位帧缓存，以 50 MHz 时钟每 200 us 扫描一行，
并将软件的高有效行位图取反后输出到低有效的 `ledm_data`。
`led_matrix_demo` 在四块矩阵上分别显示 `R`、`I`、`S`、`C`，并作为
cloud_pynq 的默认程序；重新构建使用统一的
`asmbin -> Top.v -> Vivado bitstream` 流程。

## 13. Vivado 2020.1 + cg_fpga 1.6.3

- 2018 IP 路径：`online-fpga/cg_fpga_2018_1_6_3/`
- VLNV：`educg.net:user:cg_fpga:1.6.3`
- 2020.1 脚本：
  - `lab1/vivado/cloud_pynq/open_project_2020.tcl`
  - `lab1/vivado/cloud_pynq/generate_bitstream_2020.tcl`
- 2018 IP 多出的 `PS7_MAXI` / `aresetn` 保持未连接，由 Vivado 自动 tie-off，
  不要引到顶层引脚。
- `Top.scala` 的 `VerilogGenerator` 支持程序名参数：
  `sbt "runMain board.cloud_pynq.VerilogGenerator seg_demo"`。
- `CMakeLists.txt` 的 `C_PROGRAMS` 补充 `seg_demo`。
- 已生成：
  - `bitstreams/cloud_pynq_2020_seg_demo.bit`
  - `bitstreams/cloud_pynq_2020_led_matrix_demo.bit`
  - `bitstreams/cloud_pynq_2020_say_goodbye.bit`
- 三个工程 `cp2020_led` / `cp2020_seg` / `cp2020_say` 已加入 `.gitignore`。
- 时序（Vivado 2020.1，50 MHz）：WNS 分别约 +0.304 / +0.757 / +0.800 ns，
  均满足约束。

## 14. LED 控制 demo 合并

- `seg_demo.c` 和 `led_matrix_demo.c` 合并为 `led_control_demo.c`。
- 程序同时初始化 8 位数码管（显示 `1 2 3 4 5 6 7 8`）和四块 16x16
  LED 矩阵（显示 `R I S C`）。
- 修正 LED 矩阵水平镜像：软件位图改为 bit0 对应最左列，
  使用 `pixels |= 1u << col`，硬件仍负责取反为低有效 `ledm_data`。
- `CMakeLists.txt`、`Top.scala` 默认程序、`README.md` 同步更新。
- 新 bitstream：`bitstreams/cloud_pynq_2020_led_control_demo.bit`；
  旧的 `seg_demo` / `led_matrix_demo` bitstream 已不再生成。
