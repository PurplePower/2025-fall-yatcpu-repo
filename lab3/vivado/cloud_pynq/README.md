# lab3 cloud_pynq

lab3 的五级流水线 CPU 在 cloud_pynq 上由 `clk_wiz` 提供 **10 MHz** 专用时钟。

仓库保留学生填空框架。重新生成 RTL、运行 CPU 功能测试或构建比特流前，
需要先完成 lab3 的实验填空；下述验证结果对应已完成填空的实现。
现有比特流包含修复后的完整 CPU，不受框架恢复影响。

## 时钟与存储

- 平台输入：`cg_fpga.clk_100M` = 100 MHz
- `clk_wiz` 输出 1：40 MHz，用于数据存储 / UART / SegDisplay / LedMatrixDisplay / ROMLoader / MTime
- `clk_wiz` 输出 2：10 MHz，直接作为 CPU / Timer 时钟（保留 lab2 的 10 ms / 1 s C 程序计时语义）
- 指令：直接使用组合读的 `InstructionROM`
- 数据：保留 `AsyncMemory`（组合读）
- MTime：1 MHz，MMIO `0xC0000000`（低 32 位）/ `0xC0000004`（高 32 位）

### Vivado 2022.2 时钟修复（2026-11-10）

原 CPU 时钟来自寄存器 /4 分频位。本次按方案 A 改为 `clk_wiz_0` 的第二路
`clk_10m` 直接驱动 `Top` 的 `cpu_clock`；CPU 域翻转信号经三级同步得到单周期
`CPU_tick`，只用于 MMIO/store 限定。`clocks.xdc` 改为对 40 MHz 与 10 MHz
的 1:4 跨域路径加 multicycle 约束，并标记复位/CPU_tick 同步器。
`open_project.tcl` 会自动升级缺少 CLKOUT2 的旧工程。
本次已用 Vivado 2022.2 生成
`bitstreams/cloud_pynq_2022.2_led_control_demo.bit`：
WNS +1.054 ns，WHS +0.008 ns，setup/hold 违例均为 0。

## 构建 C 程序

```bash
cd lab3/csrc
cmake -S . -B build_cloud_pynq -G "MinGW Makefiles" \
      -DCMAKE_TOOLCHAIN_FILE="$PWD/toolchain.cmake"
cmake --build build_cloud_pynq --target led_control_demo --parallel
```

`led_control_demo.asmbin` 会复制到 `lab3/src/main/resources/`。

## 生成 Verilog

```bash
cd lab3
sbt "runMain board.cloud_pynq.VerilogGenerator led_control_demo"
```

也可以不传参数，默认就是 `led_control_demo`。

## Vivado

Vivado 2022.1 + cg_fpga 1.4：

```bash
cd lab3/vivado/cloud_pynq
vivado -mode batch -source open_project.tcl
vivado -mode batch -source generate_bitstream.tcl
```

Vivado 2020.1 + cg_fpga 1.6.3：

```bash
cd lab3/vivado/cloud_pynq
vivado -mode batch -source open_project_2020.tcl
vivado -mode batch -source generate_bitstream_2020.tcl
```

`clocks.xdc` 对 clk_wiz 的 40 MHz 主时钟与 10 MHz CPU 时钟之间的 1:4 跨域
路径声明 multicycle，并标记两个时钟域的复位释放同步器和 `CPU_tick` 同步器。
40 MHz 主时钟由 block design 的时钟 IP 约束提供。

CPU 的写入和 UART 读请求通过同步后的单周期 `CPU_tick` 限定，避免同一次访存
在快时钟域重复处理；Timer 与 CPU 同域，直接接收请求。

## 演示程序

- `led_control_demo`：Timer 中断驱动 RISC 动画，UART 回显
- `say_goodbye`：UART 播放与 s/p/r 控制
- `pi`：MTime 输出真实耗时

## 比特流交付（2026-11-06）

以下交付使用 Vivado 2020.1 / cg_fpga 1.6.3 / `xc7z020clg400-1`，
主时钟 40 MHz，CPU/Timer 10 MHz，UART 9600 8-N-1。
两份比特流均包含本次中断修复：trap/mret 重定向冲刷 IF2ID，
存在 PC 停顿时 CLINT 延迟接受中断和提交 CSR 更新。

| 程序 | 比特流 | WNS | WHS | 正式 ROM 功能仿真 |
|---|---|---:|---:|---|
| say_goodbye | [cloud_pynq_2020_say_goodbye.bit](bitstreams/say_goodbye/cloud_pynq_2020_say_goodbye.bit) | +1.205 ns | +0.022 ns | 播放、一秒行间隔、暂停/继续、忽略无效按键、播放中及结束后重播均通过 |
| pi | [cloud_pynq_2020_pi.bit](bitstreams/pi/cloud_pynq_2020_pi.bit) | +1.205 ns | +0.003 ns | 正确输出 20 位小数，计算位数与周期/MTime 比例检查通过 |

两个设计的 setup/hold/pulse-width 违例数均为 0，完整 `check_timing` 各项均为 0。
DRC 没有 Error/Critical Warning，保留平台 IP 的 1 项 warning 和 3 项 advisory。
lab3 全部 25 项软件测试通过，其中新增回归覆盖中断与 load-use 停顿同周期、
残留分支冲刷、连续两次中断返回及 mret 后错误路径冲刷。

`pi` 按默认参数计算 200 位、输出小数点后 20 位，MTime 为 1 MHz，
正式仿真输出 `3.14159265358979323846`，计时 8487.578 ms。
`say_goodbye` 的正式仿真测得两行起始间隔为 39,999,033 个主时钟周期，约一秒。

每份交付目录包含 `build_manifest.json`、`SHA256SUMS.txt`、固件、源码快照、
仿真源码及日志、最终时序/DRC/时钟报告和最终布线 checkpoint。
此前未通过功能仿真的版本已移出交付目录，故障记录保留在根目录 `TODO.md`。
尚未在云平台实际烧录验证。
