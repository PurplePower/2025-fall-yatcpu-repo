# lab2 cloud_pynq 迁移记录

实施日期：2026-11-01。操作说明见 `vivado/cloud_pynq/README.md`，初始方案保留在
`cloud_pynq-plan.md`。提交迁移时已恢复 lab1/lab2 的学生填空框架，八个填空文件与迁移前版本
完全一致。本次提交只包含平台支持、配套程序、测试和文档，不包含助教答案、生成 RTL、
Vivado 工程缓存或比特流。下述功能测试及比特流验收使用临时填入助教答案的完整 CPU；
学生需要完成填空后再运行功能测试和生成硬件。

## 板级与配套代码

- 新增 `board/cloud_pynq/Top.scala`，复用 lab1 平台端口、UART 交叉连接、数码管、矩阵和 ROMLoader。
- 主时钟改为 40 MHz，CPU/Timer 共用寄存器分频产生的 10 MHz 时钟。相较最初的 50/12.5 MHz 方案，降低频率以增加 CSR/中断路径的时序裕量；保持 /4 比例和外设功能。
- Timer 的接口、填空和答案语义保持原样，板级改变其实例化时钟。
- 增加 generated-clock 约束和复位同步器 ASYNC_REG 属性；复位同步器异步置位、同步释放，功能逻辑使用域内同步复位，避免异步复位直接驱动 BRAM 地址/写控制。
- CPU 启动就绪信号再经过一级主时钟寄存后才参与 RAM/ROMLoader 选择，避免复位同步器的异步置位直接改变 RAM 启动通路。
- RAM、UART、数码管、矩阵的 store 只产生一个主时钟写脉冲；Timer 同 CPU 时钟，不套用主时钟脉冲。
- UART 迁移 8-N-1、TX ready、RX valid 和 `UART_STAT`。新增外设专用 `receive_read` 输入，实际 RX load 才清除接收状态；所有 lab2 UART 实例均已连接。学生 CPU/RAMBundle 接口不变。
- z710/z710v1.3 改用明确的设备译码连接，修复 z710v1.3 第二次总线批量连接覆盖 UART 的问题，接回 Timer 和中断。实体板波特率保持 115200。
- 复用 `SegDisplay`、`LedMatrixDisplay`、`ClockRatioCheck` 和 Memory 时序说明。
- 合并 MMIO 头文件，保留 Timer/VRAM/VA 宏，新增 SEG/LEDM/UART 状态定义。
- 迁移 GCC + CMake 构建，使用 `rv32i_zicsr` / `ilp32`；Windows 与 Linux 构建入口均保留，不自动删除既有构建目录。
- startup 使用 `__stack_top = 0x8000` 并清零 BSS；修复默认 `trap_handler` 标签及陷阱帧 `s0` 保存/恢复。链接脚本收集 `.sbss*`、给栈留出至少 4 KiB、检查与页表预留区冲突，并将数据段末尾补齐到 32 位字，防止 ROM 读取丢掉最后几个字节。
- 所有默认构建程序已重新编译，受影响的 `.asmbin` 与源码一起更新。

## 每秒变化的 led_control_demo

启动显示 RISC 与数字 1～8。Timer limit 设置为 `10_000_000 - 1`，每次机器定时器中断
只递增 `seconds`，主循环刷新显示，四种样式循环：

1. R I S C，正像，数码管 1～8。
2. I S C R，正像，数码管 2～9。
3. S C R I，反像，数码管 3～A。
4. C R I S，反像，数码管 4～B。

UART 中断收取一个字符，主循环回显一次。没有在 ISR 中等待串口发送或执行显示扫描。
显示扫描由硬件独立完成。Timer 禁用只屏蔽中断，不清零计数器，保持课程语义；首个事件相对于
软件使能时刻不保证恰好一秒，连续事件之间的周期为一秒。

## 已完成验证

- 提交前恢复学生框架后，`sbt "Test / compile"` 通过；以下功能测试结果来自填入助教答案的开发版本。
- lab1 原有基线：12/12，通过；本轮没有修改 lab1 板级实现。
- lab2 最终 `sbt test`：15/15，通过，14 个 suite。
- 新覆盖：UART 帧/发送次数/接收确认、Timer 周期与禁用恢复、陷阱寄存器恢复、矩阵扫描、四种动画样式反复循环、UART 中断单字节回显。
- 原生 Verilator 使用正式 `Top.v` 与正式 `led_control_demo.asmbin`，运行 81,000,000 主时钟周期并额外复位重启。可见样式变化发生在 40,320,002 和 80,320,002 周期，差值恰为 40,000,000（一秒）；额外延迟来自软件刷新和数码管扫描。矩阵旋转及 RST 恢复初始样式均通过。
- 仿真快版只用于 `sbt test`，正式比特流不使用 `led_control_demo_fast`。
- 根目录日志：`lab2-cloud-tests.log`、`lab2-native-timer.log`、`lab2-cmake-build.log`、`lab2-vivado-2020.log`。

## Vivado 交付记录

使用 Vivado 2020.1，器件 `xc7z020clg400-1`，平台 IP 为 `cg_fpga:1.6.3`，
最终工程目录 `vivado/cloud_pynq/cp2020_lab2`。综合使用 `Flow_PerfOptimized_high`，
实现使用 `Performance_Explore`。最终启动控制修正使用此前已通过时序的 routed DCP 进行增量实现。
Vivado 在高复用模式下自动覆盖布局/布线指令，使用其增量 RuntimeOptimized 流程。
全新工程不需要该 DCP，仍可用同一 Tcl 完整构建。增量布线后有一条 -0.013 ns 的 hold 违例，
通过 `phys_opt_design -hold_fix` 插入一个物理缓冲修复。该步骤已纳入
`finalize_bitstream.tcl`，由生成脚本自动调用；没有放宽时钟约束或添加 false path。

最终验收完成于 2026-11-01 02:52（UTC+8）：

- 比特流：[cloud_pynq_2020_led_control_demo.bit](vivado/cloud_pynq/bitstreams/cloud_pynq_2020_led_control_demo.bit)，4,045,676 字节。
- SHA-256：`937FDBCC42AB10275C9B79F1B7BD19B31DB331F7F384EA2C63C93E27D793EE30`。
- WNS **+1.055 ns**，WHS **+0.011 ns**，TNS/THS **0**；setup/hold/pulse-width 违例数均为 0。
- 主时钟周期 25 ns，CPU/Timer 周期 100 ns；无时钟引脚和未约束内部端点均为 0。
- 最终 DRC：0 errors，1 项 `RTSTAT-10` warning（平台 IP 的 33 条无可布线负载网络），3 项 BRAM WRITE_FIRST advisory（`REQP-165` / `REQP-181`）；异步复位驱动 RAM 的 `REQP-1839` 已消除。
- [构建清单](vivado/cloud_pynq/bitstreams/build_manifest.json)记录工具版本、固件、源文件和 checkpoint 哈希；[校验和](vivado/cloud_pynq/bitstreams/SHA256SUMS.txt)与[最终报告](vivado/cloud_pynq/bitstreams/reports/)一并保存。
- 已核对正式 `.asmbin` 与 ROM 初始化文件完全一致（除 ROM 生成器附加的三个 NOP），并核对比特流及报告均晚于最终 RTL。
- LED 演示最终物理优化后的 checkpoint 已归档为 `bitstreams/reports/led_control_demo_routed.dcp`（相对 `vivado/cloud_pynq`）；后续 say_goodbye 构建复用了工程目录，复查 LED 演示请打开归档文件。

本次最终物理优化与写出日志为 `lab2-vivado-2020.log`；此前完整综合/增量实现日志保留为
`lab2-vivado-2020-before-hold-fix.log`。构建进程已正常退出。

Vivado 2022.1 + cg_fpga 1.4 的入口已迁移并通过综合；对比构建在 2020.1 完成有效布线后停止，未将其作为最终比特流验收结果。
云平台网页实际烧录尚未验证；音频和云端视频仍关闭，保留实体板原有 HDMI 程序。
中断控制器仍采用课程简化语义，没有扩展为完整特权架构或中断事件队列。

## say_goodbye 定时播放与 UART 控制（2026-11-01）

- `csrc/say_goodbye.c` 保留仓库原有两行歌词片段，改为启动后自动播放，每秒开始一行，播放一次后停止。
- Timer 使用 10 MHz CPU 时钟，limit 99999，每 10 ms 中断一次；累计 100 次中断控制行间隔，同时每 tick 最多发送一个字符，保留云端 UART 节流。
- `s` 暂停并保留行内位置；`p` 继续，已播完时保持停止；`r` 输出 CRLF 并从第一行重播。无关字符、CR/LF 被忽略。UART 中已经接受的字节会完成发送。
- ISR 只维护 tick 和单字符接收事件，前台以非阻塞方式发送。接收事件用一个字保存序号和字符，避免前台清除 pending 标志与新中断相互覆盖。
- 修复链接脚本遗漏 `.srodata*` 的问题：GCC 将小型常量表（本次为歌词指针表）放在该段，原脚本未收集它，导致 `objcopy -j .text -j .data` 输出的固件缺失指针表。现已将其并入 `.data`，所有默认程序及测试程序重新构建。
- 新增 `make native-goodbye-test`，用独立生成的正式 RTL/ROM 通过 UART 引脚验证，不使用缩短周期的测试固件；生成物位于 `target/goodbye-*`，不覆盖 LED 演示的 Vivado RTL。

验证结果：

- `sbt test`：15/15 成功，14 个 suite，日志 `lab2-goodbye-tests.log`。
- 原生 Verilator：正式固件两行起始间隔为 39,999,033 个主时钟周期（40 MHz，约 0.999976 秒；UART 帧启动引入微小量化差异）。歌词内容、CRLF、一次播放后停止均通过。
- 实际 RX 输入测试通过：行内暂停/继续不丢字或重复，重复 `s`、无关字符、CR/LF 不恢复播放，EOF 后 `p` 不重播，EOF 后及发送中 `r` 均换行从头重播且最终停止。
- 日志：`lab2-goodbye-build.log`、`lab2-goodbye-native-build.log`、`lab2-goodbye-native.log`。

程序修改阶段更新了 `say_goodbye.asmbin`，后续比特流交付见下节。歌词列表、控制语义、构建和验证命令见
`vivado/cloud_pynq/README.md` 的 say_goodbye 小节。

## say_goodbye 比特流交付（2026-11-01）

使用 Vivado 2020.1、cg_fpga 1.6.3、`xc7z020clg400-1`，复用 `cp2020_lab2` 工程，
重新生成 `say_goodbye` RTL、综合和增量实现。增量基线为先前通过时序的 LED 演示最终 checkpoint，
基线副本保存在 `target/cloud_goodbye_baseline_routed.dcp`。最终保持时间修复和比特流写出
由 `finalize_bitstream.tcl` 自动完成，没有放宽时钟约束。

- 完成时间：2026-11-01 18:46（UTC+8），Vivado 正常退出，最终时序检查通过。
- 比特流：[cloud_pynq_2020_say_goodbye.bit](vivado/cloud_pynq/bitstreams/say_goodbye/cloud_pynq_2020_say_goodbye.bit)，4,045,676 字节。
- SHA-256：`83BA22D56D0DB271045B1CC89553435F6E65D48FD6642DC764CAAE5138381E98`。
- 时钟：主时钟 40 MHz，CPU/Timer 10 MHz；Timer limit 99999，每 100 次中断开始一行，UART 9600、8-N-1。
- 最终 WNS +0.708 ns，WHS +0.004 ns，TNS/THS 0；setup/hold/pulse-width 违例数为 0。
- `check_timing` 各检查类别均为 0，包括缺失时钟及未约束内部端点。
- 最终 DRC：0 errors，1 项 `RTSTAT-10` warning，3 项 `REQP-165` / `REQP-181` advisory，均为平台 IP 项目；没有 `REQP-1839`。
- 已校验 Vivado RTL 与正式 UART 仿真 RTL 字节级一致，ROM 初始化文件与 `say_goodbye.asmbin` 一致，正式固件沿用上述已通过的 15 项软件回归和 3 组原生 UART 集成验证结果。
- [构建清单](vivado/cloud_pynq/bitstreams/say_goodbye/build_manifest.json)、[校验和](vivado/cloud_pynq/bitstreams/say_goodbye/SHA256SUMS.txt)、报告、最终 checkpoint 和构建日志均归档在 `vivado/cloud_pynq/bitstreams/say_goodbye/`。
- 根目录构建日志：`lab2-goodbye-bitstream-rtl.log`、`lab2-goodbye-vivado-2020.log`。

该比特流播放仓库原有的两行歌词片段，支持 `p` 继续、`s` 暂停、`r` 换行后从头重播。
此前交付的 LED 演示比特流及报告保留。云平台实际烧录与网页 UART 显示仍需上板验证。

## pi 比特流交付（2026-11-06）

- 使用 Vivado 2020.1、cg_fpga 1.6.3、`xc7z020clg400-1`，在独立源码/RTL/ROM 快照和新工程中完整构建。
- 比特流：[cloud_pynq_2020_pi.bit](vivado/cloud_pynq/bitstreams/pi/cloud_pynq_2020_pi.bit)。主时钟 40 MHz，CPU/Timer 10 MHz，MTime 1 MHz，UART 9600 8-N-1。
- 默认计算 200 位、输出 20 位小数；正式 ROM 的 UART 引脚仿真输出 `3.14159265358979323846`，计时 7206.324 ms，周期计数/MTime 比例检查通过。
- 最终 WNS +1.247 ns、WHS +0.051 ns、WPWS +3.000 ns，setup/hold/pulse-width 违例数均为 0；完整 `check_timing` 各项均为 0。
- DRC 无 Error/Critical Warning，保留平台 IP 的 1 项 warning、3 项 advisory。
- 固件、源码快照、源码哈希、比特流 SHA-256、仿真日志、最终报告和布线 checkpoint 均保存在 `vivado/cloud_pynq/bitstreams/pi/`；尚未在云平台实际烧录。

## 提交前恢复学生框架与清理（2026-11-06）

- 恢复 CSR、Execute、CLINT、Timer 四个填空文件，与仓库原学生框架逐字节一致。
- 已核对本地 `misc/lab2_ans.yaml` 能完整还原恢复前的实现；参考答案继续由 Git 忽略。
- 学生框架的 `sbt "Test / compile"` 通过；上述功能仿真和比特流结果对应完成填空后的 CPU。
- 清理根目录和 Vivado 入口目录中的临时日志及 journal；本文此前列出的根目录临时日志已移除，交付目录中的归档验证记录保留。
- 补齐 z710/z710v1.3 生成 RTL、时序实验工程和报告目录的忽略规则。现有比特流及完整交付包保留，不纳入源码提交。
