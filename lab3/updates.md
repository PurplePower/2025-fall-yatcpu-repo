# lab3 迁移与新增功能记录

更新时间：2026-11-06

本文记录参照 `lab2/updates.md` 将 lab2 已有改进同步到 lab3 的内容，以及新增的
MTime 外设、`pi.c` 计算程序和 cloud_pynq 支持。

## 1. C 程序与构建链同步

lab3 的 `csrc` 已与 lab2 对齐：

- 新增 `simpletest.c`
- 新增 `led_control_demo.c`
- 新增 `trap_registers.S`
- 新增 `pi.c`
- `say_goodbye.c` 更新为 lab2 的 UART/Timer 完整版本
- `init.S` 同步：
  - `sp` 使用链接脚本提供的 `__stack_top`
  - 启动时清零 `.bss`
  - 修复默认 `trap_handler` 符号
  - trap 上下文保存/恢复改用 `s0`，修复原先把 `tp` 保存两次的问题
- `link.lds` 同步为 lab2 的统一内存布局：
  - `.text` 从 `0x1000` 开始
  - `.data` 页对齐，包含 `.rodata`、`.srodata`、`.sdata`
  - `.bss` 从 `0x6000` 开始，包含 `.sbss` 和 `COMMON`
  - 初始栈顶 `__stack_top = 0x8000`
  - 对 text/data、BSS 和栈空间做链接期检查
- `toolchain.cmake`、`build.sh`、`build.bat` 同步为 lab2 的
  `riscv64-unknown-elf-gcc` + CMake 构建方式
- `CMakeLists.txt` 增加：
  - `simpletest`
  - `led_control_demo`
  - `pi`
  - `led_control_demo_fast`（`DEMO_TIMER_TICKS=20000u`）
  - `trap_registers`
  - `test_programs`
  - `cloud_pynq` 便捷目标
- `say_goodbye`、`led_control_demo`、`pi` 使用 `-O2`
- `pi` 额外使用 `-fno-builtin -fno-stack-protector`

构建产物会写入 `lab3/src/main/resources/*.asmbin`。

## 2. MMIO 与公共库

`csrc/mmio.h` 已同步 lab2 的完整 MMIO 映射，并增加 MTime：

```text
0x00000000  RAM
0x20000000  VRAM
0x40000000  UART
0x60000000  SegDisplay
0x80000000  Timer
0xA0000000  LED Matrix
0xC0000000  MTime
```

新增的公共函数：

- UART：
  - `uart_tx_ready()`
  - `uart_try_putc()`
  - `uart_putc()`
  - `uart_puts()`
  - `uart_put_uint()`
- MTime：
  - `read_mtime_lo()`
  - `read_mtime()`

UART 写操作统一检查 `UART_STAT.TX_READY`，不再使用固定延时。`pi.c`、
`say_goodbye.c`、`led_control_demo.c` 已统一使用这些公共函数。

## 3. 外设同步

lab3 `peripheral` 增加/同步：

- `UART.scala`：同步为 lab2 版本
  - TX ready / RX valid 状态
  - `UART_STAT`（bit0 TX ready，bit1 RX valid）
  - `receive_read` 输入
- `Timer.scala`：同步为 lab2 版本
- `Memory.scala`：同步 lab2 的同步读时序说明
- `SegDisplay.scala`：新增
- `LedMatrixDisplay.scala`：新增
- `ClockRatioCheck.scala`：新增
- `MTime.scala`：新增
- `AsyncMemory.scala`：新增，组合读数据存储器

lab3 的 z710、z710v1.3、basys3、pynq 顶层也补充了：

- MTime 的 `deviceSelect = 6` 映射
- lab2 UART 的 `receive_read` 连接
- 部分板级 Verilog 生成器和测试所需接口

## 4. MTime：1 MHz MMIO 时间计数器

### 设计

`peripheral/MTime.scala`：

```scala
class MTime(clockFreqHz: Int, tickHz: Int = 1000000) extends Module
```

- 内部维护 64 位 `mtime`
- 输入时钟频率由板级 Top 传入
- 如果 `clockFreqHz` 能被 `tickHz` 整除，使用整数分频，无抖动
- 否则使用 32 位分数累加器，保证平均 tick 频率为 1 MHz
- MMIO 布局：

```text
0xC0000000 -> mtime[31:0]
0xC0000004 -> mtime[63:32]
```

### 各板时钟参数

| 板子 | 传入 MTime 的时钟 | 分频 |
|---|---:|---:|
| z710 | 25 MHz | 25 |
| z710v1.3 | 50 MHz | 50 |
| basys3 | 100 MHz | 100 |
| cloud_pynq | 40 MHz（clk_wiz 输出） | 40 |

### C 接口

```c
#define MTIME_BASE 0xC0000000u
#define MTIME_LO   ((volatile unsigned int *) (MTIME_BASE + 0))
#define MTIME_HI   ((volatile unsigned int *) (MTIME_BASE + 4))
#define MTIME_HZ   1000000u

unsigned int read_mtime_lo(void);
unsigned long long read_mtime(void);   /* 高-低-高读取，避免撕裂 */
```

1 tick = 1 µs。`read_mtime_lo()` 的 32 位回绕时间约 71.6 分钟，足够当前
pi 基准使用。

## 5. pi 计算程序

文件：`csrc/pi.c`

### 算法

- Rabinowitz–Wagon 十进制 spigot 算法
- 带回 `predigit` / `nines` 进位修正
- 默认：

```c
PI_COMPUTE_DIGITS = 200   /* 实际计算的位数，用于计时 */
PI_PRINT_DIGITS   = 20    /* 打印的小数位数 */
```

正常输出：

```text
3.14159265358979323846
```

### 软件模拟 M 扩展

目标为 RV32I + Zicsr，无硬件 M 扩展。`pi.c` 实现了：

- `__mulsi3`
- `__udivsi3`
- `__umodsi3`
- `__divsi3`
- `__modsi3`
- `memset`
- `memcpy`

这些函数只用移位、加减、比较和分支实现，反汇编中不出现 `mul/div/rem`
指令。

### 计时

- 计算前后读取 `read_mtime_lo()`
- 输出：

```text
computed_digits = 200
timebase_hz = 1000000
cycles = ...
elapsed_us = ...
elapsed_ms = .... ms
```

- `cycles` 仍来自 `cycle` CSR，仅用于观察 CPU 周期数
- 真实耗时来自 MTime，与 CPU 主频、分频和板子无关
- 不再需要 `CPU_FREQ_HZ` 编译宏

### 构建

```bash
cd lab3/csrc
cmake -S . -B build_cloud_pynq -G "MinGW Makefiles" \
      -DCMAKE_TOOLCHAIN_FILE="$PWD/toolchain.cmake"
cmake --build build_cloud_pynq --target pi --parallel
```

可调整计算规模：

```bash
cmake -DPI_COMPUTE_DIGITS=100 -DPI_PRINT_DIGITS=20 ...
```

### 验证

- 主机测试输出 `3.14159265358979323846`
- RISC-V GCC 交叉编译无未解析符号
- QEMU 运行验证算法和输出格式
- `pi.asmbin` 已生成到 `lab3/src/main/resources/`

## 6. lab3 cloud_pynq

新增 `board/cloud_pynq/Top.scala` 和 `vivado/cloud_pynq`。

以下记录最初的 40 MHz CPU 方案；当前已恢复 CPU /4 分频，见第 9 节。

### 时钟与存储

- 平台输入：100 MHz
- `clk_wiz` 输出：40 MHz
- **CPU 不再做 /4 分频**，直接运行在 40 MHz
- Memory / UART / SegDisplay / LedMatrixDisplay / ROMLoader / MTime：40 MHz
- Timer：10 MHz，保留 lab2 的 10 ms / 1 s C 程序计时语义
- UART：9600

由于 CPU 与 BRAM 同频，当前五级流水不能直接使用同步读 `Memory`，因此：

- 指令取指直接使用组合读 `InstructionROM`
- 数据存储使用组合读 `AsyncMemory`

`AsyncMemory` 用组合读 `Mem` 实现，因此不依赖 CPU 比 Memory 慢。
代价是 32 KiB 数据存储会综合为 LUTRAM，占用较多 LUT，时序也比 BRAM 紧张。
如果资源或时序不合适，替代方案是保留 `SyncReadMem` 并增加 memory wait 阶段，
或者接受 CPU /2。

### Vivado

已添加：

- `open_project.tcl`
- `open_project_2020.tcl`
- `generate_bitstream.tcl`
- `generate_bitstream_2020.tcl`
- `finalize_bitstream.tcl`
- `clocks.xdc`
- `README.md`

`clocks.xdc` 不再包含 lab2 的 `cloud_cpu` generated-clock 约束，只保留复位
同步器的 `ASYNC_REG`。`finalize_bitstream.tcl` 也移除了对 `cloud_cpu` 的检查。

### Makefile

lab3 `Makefile` 增加：

- `PROGRAM ?= led_control_demo`
- `VIVADO ?= vivado`
- `z710v1.3`
- `cloud_pynq`
- `bitstream-cloud_pynq`
- `bitstream-cloud_pynq-2020`

## 7. 测试与验证

lab3 新增/同步测试：

- `UARTTest`
- `LedMatrixDisplayTest`
- `TimerPeriodTest`
- `CloudPynqTest`
- `CloudGoodbyeVerilog`

结果：

- lab3 `sbt test`：24/24 通过
- lab2 `sbt test`：15/15 通过
- lab3 z710、z710v1.3、basys3、cloud_pynq 的 Verilog 生成均成功
- C 程序使用 RISC-V GCC 全部构建成功

`CloudPynqTest` 当前验证：

- `led_control_demo` 能启动
- 数码管初始化正确
- Timer limit / enabled 配置正确

40 MHz 下 Timer 中断动画的完整时序没有作为强断言，建议后续单独验证五级流水
在 40 MHz 下的中断行为。

## 8. 已知限制

- MTime 目前接入 z710、z710v1.3、basys3、cloud_pynq；pynq、verilator 未接入
- `UART.put_uint()` 内部使用 `/`、`%`，只有 `pi.c` 使用它并自带软件 M 扩展；
  其他程序若使用也需要软件乘除或 libgcc
- cloud_pynq 的 32 KiB `AsyncMemory` 使用 LUTRAM，面积和时序代价明显高于
  BRAM；这是“无 CPU 分频 + 不改流水线”的折中
- cloud_pynq 的 Timer 中断动画在 40 MHz 下尚未做完整断言
- `CloudPynqTest` 目前只做显示/Timer 初始化检查，不检查 UART 回显和四路动画

## 9. cloud_pynq 恢复 CPU /4 分频与派生时钟约束

- `clk_wiz` 仍输出 40 MHz；CPU 与 Timer 共用 `CPU_clkdiv(1)`，运行在 10 MHz。
- CPU 域增加异步置位、同步释放的复位同步器，启动前屏蔽 CPU 访存与中断。
- 数据存储、UART、显示、ROMLoader 和 MTime 保持 40 MHz；保留组合读的
  `InstructionROM` 与 `AsyncMemory`，本次不切换存储实现。
- 快时钟域中的 CPU 写入和 UART 读请求用 `CPU_tick` 限定为一个主时钟周期，
  避免一次 CPU 访问被重复处理；Timer 与 CPU 同域，取消原有写脉冲展宽。
- `clocks.xdc` 在分频寄存器高位 Q 上声明 `cloud_cpu`，用
  `-edges {3 7 11}` 描述 /4 边沿关系；CPU 和 Timer 均受此派生时钟约束。
- 补齐 CPU 复位同步器的 `ASYNC_REG`，恢复生成 bitstream 前检查
  `cloud_cpu` 是否存在的逻辑。

恢复分频后已重新生成 cloud_pynq Verilog，`CloudPynqTest` 的显示与 Timer
初始化检查通过。Vivado 2022.1 独立综合 Top 后加载 XDC，确认 `cloud_cpu`
周期为 100 ns，覆盖 CPU 和 Timer，`no_clock` / `multiple_clock` 均为 0。
本次没有重新布局布线或生成 bitstream；第 7 节的完整测试结果属于此前版本，
完整中断动画仍需另行验证。

## 10. 补齐 z710v1.3 的 CPU 派生时钟约束

先前时序分析发现 lab2、lab3 的 z710v1.3 XDC 仅声明板级 50 MHz 时钟，
缺少 CPU 分频时钟，导致 CPU 内部时序未被完整检查。本次分别补齐：

- `lab2/vivado/z710v1.3/z710v1.3.xdc`：在分频寄存器高位 Q 上声明
  `cpu_clk`，`-edges {3 7 11}`，周期 80 ns、占空比 50%。
- `lab3/vivado/z710v1.3/z710v1.3.xdc`：沿 CPU 寄存器堆时钟网找到实际驱动，
  声明 `cpu_clk`。该板现有实现使用 `CPU_clkdiv == 0` 的单周期脉冲，
  因而采用 `-edges {7 9 15}`，周期 80 ns、占空比 25%。

此次仅补充这两块板的约束，不改动其分频电路。使用先前保存的 Vivado 2022.1
综合网表加载更新后的 XDC，两个设计的 `no_clock` / `multiple_clock` 均为 0。

## 11. 修复中断入口并交付 cloud_pynq 比特流（2026-11-06）

正式 `say_goodbye` ROM 仿真发现：首次 Timer 中断将 PC 重定向到入口后，
IF2ID 中保留的主程序分支又把 PC 拉回主循环，MIE 已清零，程序无法播放。
另一个相关问题是数据冒险停顿阻止 PC 跳转时，CLINT 仍可能提前提交中断状态。

本次修改：

- `fivestage_final/Control.scala`：普通指令触发 trap/mret 重定向时也冲刷 IF2ID。
- `fivestage_final/CLINT.scala` / `CPU.scala`：连接 `pc_stall`，停顿期间延迟 trap/mret 接受和对应 CSR 更新。
- `misc/lab3_ans.yaml`：同步 Control 参考答案；原先暂缓的源寄存器读地址优化保持暂缓。
- 新增 `InterruptRedirectTest`，覆盖一拍中断请求与 load-use 停顿重叠、残留分支冲刷、连续两次中断返回和 mret 后错误路径冲刷。

验证结果：

- lab3 `sbt test`：25 项全部通过。
- 正式 `say_goodbye` ROM 的 UART 引脚仿真：播放、一秒行间隔、结束停止、暂停/继续、忽略无效按键、播放中及结束后的重播均通过。
- 正式 `pi` ROM 的 UART 引脚仿真：输出 `3.14159265358979323846`，计算 200 位，周期/MTime 比例正确，计时 8487.578 ms。

使用 Vivado 2020.1 / cg_fpga 1.6.3 / `xc7z020clg400-1` 重新综合修复后的 CPU，
并基于先前通过时序的 checkpoint 做增量实现。主时钟 40 MHz，CPU/Timer 10 MHz，
UART 9600 8-N-1；原时钟约束保持不变。

| 程序 | 比特流 | WNS | WHS |
|---|---|---:|---:|
| say_goodbye | [cloud_pynq_2020_say_goodbye.bit](vivado/cloud_pynq/bitstreams/say_goodbye/cloud_pynq_2020_say_goodbye.bit) | +1.205 ns | +0.022 ns |
| pi | [cloud_pynq_2020_pi.bit](vivado/cloud_pynq/bitstreams/pi/cloud_pynq_2020_pi.bit) | +1.205 ns | +0.003 ns |

两份实现的 WPWS 均为 +3.000 ns，setup/hold/pulse-width 违例数均为 0，完整
`check_timing` 各项均为 0。DRC 无 Error/Critical Warning，保留平台 IP 的
1 项 warning、3 项 advisory。固件、构建清单、SHA-256、源码快照、验证源码及日志、
最终报告和布线 checkpoint 分别归档在 `vivado/cloud_pynq/bitstreams/<程序名>/`。
未通过功能验证的早期版本已移出交付目录；尚未在云平台实际烧录验证。

## 12. 提交前恢复学生框架与清理（2026-11-06）

- 恢复 PipelineRegister、ThreeStage、Stall、Forward、Final 共 12 个填空文件，与仓库原学生框架逐字节一致。
- 已核对本地 `misc/lab3_ans.yaml` 能完整还原恢复前的填空实现，包括 trap/mret 的 IF2ID 冲刷修复；参考答案继续由 Git 忽略。
- 保留填空区之外的 `CLINT.stall_flag_ctrl` 与 CPU 连接、中断回归测试、cloud_pynq CPU /4 分频和派生时钟约束。
- 学生框架的 `sbt "Test / compile"` 通过；第 11 节的 25 项功能测试及比特流验证对应完成填空后的 CPU。
- 清理散落的临时日志及 journal，保留比特流交付目录中的验证记录；补齐 C 构建目录、生成 RTL、Vivado 工程、时序实验报告和比特流的忽略规则。
