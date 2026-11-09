# lab2 云 FPGA 适配初步实施方案

> 实施进度（2026-11-01）：板级、外设、构建和 Timer 动画已迁移；实现阶段为增加时序裕量改为 40 MHz 主时钟、10 MHz CPU/Timer，15 项软件回归及正式程序原生长周期仿真通过；最终 Vivado 验证记录见 `updates.md`，使用说明见 `vivado/cloud_pynq/README.md`。以下保留初始方案供对照。

> 提交状态：lab1/lab2 的学生填空框架已恢复，助教答案及备份未纳入迁移提交。本文下述答案填充和测试记录描述开发期间的状态。

基于 2026-10-31 的工作区源码及 `lab1/updates.md`。本轮完成答案填充、现有软件测试和迁移评估；下述板级移植、配套修正及上板验收为待实施工作。

## 1. 已完成的开发基线

- 使用 `misc/lab1_ans.yaml` 填入 lab1 的 InstructionFetch、InstructionDecode、Execute、CPU。
- 使用 `misc/lab2_ans.yaml` 填入 lab2 的 CSR、Execute、CLINT、Timer。
- 开发时为每个文件保留 `.scala.backup`，内容为填充前的学生骨架；提交前已据此恢复学生框架，助教答案和备份仅作本地归档。
- `misc/autoans.py` 的插入操作使用子串匹配，因此 lab1 的 `// lab1(...) end` 也会被匹配。本轮已从备份重建，使用工具已有的区间替换功能清理重复插入；未修改填充工具和答案 YAML。后续应为这三个 lab1 区间配置 `end_anchor`，并避免在已填充文件上重复执行插入。
- 原有根目录 `.gitignore` 修改保持不动，不纳入迁移提交。

实际执行命令为在各 lab 目录下运行 `sbt test`，使用仓库现有 Verilator + VCD 配置，无测试跳过或后端替换。

| 实验 | 结果 | 覆盖范围 |
|---|---|---|
| lab1 | 12 成功，0 失败，10 个 suite | CPU 部件、Fibonacci、Quicksort、字节访存、LED 矩阵、两块 z710 板级仿真 |
| lab2 | 10 成功，0 失败，9 个 suite | CSR 运算、CLINT/CSR、Timer 寄存器、CPU 程序、SimpleTrap、两块 z710 板级仿真 |

日志位于仓库根目录 `lab1-baseline-test.log`、`lab2-baseline-test.log`，已被现有日志规则忽略。lab1 清理重复答案后再次全量通过。

本机实际测试使用 sbt 1.9.6、Java 17.0.9。已发现 MSYS2 RISC-V GCC、CMake，以及 `E:/Xilinx/Vivado/2020.1` 和 `2022.1` 安装目录。测试使用现有 `.asmbin`，本轮没有重新编译全部 C 程序、运行 Vivado 或验证云端烧录。板级 `SayGoodbyeTest` 主要是运行仿真，并不逐字节断言 UART 输出完整。

## 2. 移植范围与兼容原则

先实现 lab2 云平台的 Timer 中断演示和 UART 收发，复用 lab1 数码管、LED 矩阵与 Vivado 工程；保持学生需要完成的 CSR、Execute、CLINT、Timer 接口和现有题目语义。

lab1 和 lab2 都是 32 KiB RAM、入口 `0x1000`，CPU 均以地址高三位选择设备，并把设备内偏移传给外设。原有 Timer 位于 `0x8000_0000`，与 lab1 新增数码管、矩阵地址无冲突。

| lab1 改进 | lab2 同步方式 |
|---|---|
| `SegDisplay.scala`、`LedMatrixDisplay.scala`、`ClockRatioCheck.scala` | 可直接复用，迁移 LED 矩阵现有测试 |
| `Memory.scala` 同步读延迟说明 | 可直接同步；当前两份文件的功能实现相同 |
| UART 8-N-1、TX ready、`UART_STAT` | 合并到 lab2，保留中断输出，补足接收清除条件 |
| `board/cloud_pynq/Top.scala` | 以 lab1 为模板，适配 lab2 调试端口、Timer 和中断输入；不能直接复制完成 |
| `vivado/cloud_pynq/*.tcl` | 复用两套 Vivado 版本入口及 BD 连接，修正文案和 lab 路径 |
| `mmio.h` | 保留 lab2 Timer、VRAM、VA 宏，加入 SEG、LEDM、UART 状态宏 |
| `link.lds`、`init.S` | 合并合法栈顶和 BSS 清零，保留中断入口，并修复下述启动汇编问题 |
| CMake、工具链、构建脚本 | 迁移 GCC 构建方式，保留 lab2 的 `simpletest` 等程序，使用 `rv32i_zicsr` / `ilp32` |
| `say_goodbye.c`、显示 demo | 复用流控、CRLF 和云端节流；新增适合 lab2 的中断显示程序 |
| z710/z710v1.3 总线修正 | 第二阶段同步写脉冲和 UART 连接；保留各板原有频率、115200 波特率、显示和中断功能 |
| ignore 和文档 | 合并生成物规则；不复制历史 bitstream、工程缓存或已删除的专用 startup/linker 文件 |

`lab1/updates.md` 是多轮修改的累积记录，应以当前源码为准。例如当前默认程序是 `led_control_demo`；链接脚本的 `.data` 是在 `.text` 后按 4 KiB 对齐，不能假定始终为 `0x2000`。

## 3. 中断与时钟：推荐最小实现

推荐时钟分配：

```text
cg_fpga 100 MHz -> clk_wiz 50 MHz -> RAM / ROMLoader / UART / SEG / LEDM
                                      |
                                      +-> /4 -> CPU + Timer，12.5 MHz
```

现有 Timer 答案在 `count >= limit - 1` 时输出中断，在 `count >= limit` 后归零，正常正数 limit 下中断高电平约两个 Timer 周期。如果 Timer 用 50 MHz、CPU 用 12.5 MHz，直接接线有漏采窗口，现有 Timer 单测没有覆盖这种情况。

推荐在新的 cloud_pynq Top 中用 CPU 时钟实例化 Timer，不改变 Timer 填空、默认值或寄存器语义。Timer 写使能按其 CPU 时钟域处理；RAM、UART、SEG、LEDM 的写使能才使用 50 MHz 域的单次写脉冲，不能把同一套脉冲限定机械套到所有设备。

定时周期按当前实现约为 `(limit + 1) / 12_500_000` 秒。新 demo 应显式设置 limit，避免依赖旧板频率；例如一秒可设置为 `12_500_000 - 1`。选择足够长的周期，避免处理函数跨过多个周期。

若必须让 Timer 保持 50 MHz，则需在 Top 外加事件保持、CPU 采样清除和连续高电平去重，并定义屏蔽期间是否保留事件。这比同域 Timer 更复杂，不作为第一版默认方案。

中断编码继续采用现有 `Cat(uart_irq, timer_irq)` 约定，并在 ROM 装载完成前屏蔽对 CPU 的中断。当前 CLINT 按整个中断值匹配：只有值 1 识别为 Timer，其他常见非零值走外部中断。因此先分别验证 Timer 和 UART；若后续要求同时发生时不丢事件，在 Top 中定义优先级和 pending 保持，不要求学生改写 CLINT 为完整中断控制器。

## 4. 必须单独处理的配套问题

### UART 接收清除

lab1/lab2 的 Uart 都在地址为 `0x0C` 时清除接收中断，lab1 还清除 RX valid。`RAMBundle` 没有 read-enable，如果像 lab1 cloud Top 那样把所有 CPU 偏移直接接到 UART，访问其他设备的偏移 `0x0C` 也可能误清除接收状态。

第一步必须对未选中的 UART 地址做隔离。要完整限定读副作用，建议只给 Uart 增加专用接收读确认输入，在实际 UART load 时产生一次确认；更新所有 Uart 实例的连接，保持学生的 `CPUBundle`、`RAMBundle` 不变。新 Top 可依据有效指令的 load opcode、deviceSelect、偏移及 CPU/外设时序产生确认，数据读路径保持可用。该时序需要仿真确认。

首个硬件里程碑可以先演示 Timer + UART TX，随后加入经过确认的 UART RX/中断。TX ready 只保护 CPU 自身发送缓冲，不能保证云端 Uartlite 的 16 字节 FIFO 被网页及时读走，保留可调整的发送节流。

### 启动汇编和内存布局

lab2 当前 `.bss = 0x0010_0000`、初始 `sp = 0x1000_0000` 均超出 32 KiB RAM，不能把仿真通过或地址截断行为当作正确布局。迁移 lab1 的 `__stack_top = 0x8000`、BSS 清零和符号定义；检查 `.sbss*` 收集与 GCC 小数据设置，保持 `-mno-relax`，增加链接时 RAM/区域越界断言和栈空间检查。

lab1/lab2 的 `init.S` 还共有两个问题：

- 声明 `.weak trap_handler`，但默认实现标签拼作 `tran_handler`，应改成对应的 `trap_handler`。
- 陷阱帧偏移 32 重复保存/恢复 `tp`，应为 `s0`；否则中断处理函数可能破坏被中断代码的 frame pointer。

这些属于助教提供的启动设施，可修复而不改变学生题目。保留 `enable_interrupt`、`__trap_entry`、CSR 访问及 `mret`；为中断前后寄存器恢复增加有意义的回归验证。

使用 GCC 时明确 `-march=rv32i_zicsr -mabi=ilp32`，保留 lab2 程序列表。原有测试资源先保留，新的云平台 demo 使用独立程序名；统一 startup/linker 后重新生成受影响的测试二进制并跑回归，不把新旧二进制混用的通过结果当作迁移验收。

### 复位和时钟约束

复用低有效平台端口、UART 交叉接线和 `locked` 参与复位。lab1 注释描述异步复位，但源码使用通用 `Reset()`；移植时若要求异步置位、同步释放，应显式使用 `AsyncReset` 并核对生成 RTL。CPU/Timer 域的复位释放也需按其时钟处理。

沿用 /4 配置起步，但 `ClockRatioCheck` 仅检查周期比例，不能代替时序与 CDC 验证。检查生成时钟约束、分频时钟实际实现及跨域路径；不能仅凭 lab1 的历史正 WNS 就认定 lab2 时序通过。

### 显示方式和教学语义

现有 `hello.c` 用 CharacterDisplay/VRAM 显示中断信息；lab1 cloud Top 的视频关闭，直接运行它看不到原来的文本输出。第一版新增 `cloud_interrupt_demo.c`，用数码管显示中断计数、矩阵显示固定图案或事件状态、UART 输出少量日志。中断处理函数只更新计数/状态，较慢的串口输出放到主循环。

保留实体板 HDMI demo。云端 HDMI 涉及视频时钟与数据格式，暂不列为 lab2 首版验收条件。

现有 CLINT/CSR 是教学用简化实现，例如 MPIE 状态处理依赖现有初始化/测试约定。第一版保持题目与答案语义，不扩大为完整特权架构实现；补充板级验证，避免把现有测试通过解释为完整 ISA 合规认证。

## 5. 实施顺序与验收

1. **基线准备（已完成）**：本地填答案、保留骨架、两套测试全通过。后续平台代码与本地答案分开审查，发布学生版前恢复填空。
2. **板级与通用外设**：新增 lab2 的 cloud_pynq Top/Tcl，迁移 SEG、LEDM、UART、时钟检查，加入同 CPU 时钟的 Timer。生成 Verilog，验证所有输入和中断端口已连接。
3. **配套软件**：合并 MMIO、修复 startup/linker、迁移 GCC 构建，新增云端中断 demo。Windows 使用 MSYS2 的 MinGW Makefiles，Linux 使用对应原生生成器；顶层 Makefile 不硬编码为只适用于 Windows。
4. **有针对性的仿真**：保持原 10 项 lab2 测试通过；新增实际 Timer 到 CPU 的反复进入/返回测试、不同初始相位、Timer 禁用、UART 单字节发送不重复、接收状态不会被其他设备访问清除、trap 寄存器恢复，以及显示写入验证。用较小 limit 加速仿真，单独确认真实频率下周期换算。
5. **Vivado 构建**：先采用 lab1 已有成功记录的 Vivado 2020.1 + `educg.net:user:cg_fpga:1.6.3`（`online-fpga/cg_fpga_2018_1_6_3`）。保留 Vivado 2022.1 + `cg_fpga:1.4` 的另一入口与独立工程目录，避免跨版本复用已生成工程。检查 IP 状态、DRC、setup/hold 时序、未约束路径及资源报告，生成可追踪的 `.bit`。
6. **云平台验收**：RST 后稳定启动；数码管/矩阵极性正确；Timer 计数稳定递增，禁用后停止、重新使能后继续；UART 文本完整、单字符接收处理正常；中断返回后主程序持续运行。记录 Vivado/IP 版本、程序名、bitstream SHA256 和实际平台现象。

完整构建链保持为：

```text
C/汇编 -> ELF / asmbin -> Chisel elaboration -> Top.v + asmbin.txt
       -> Vivado synthesis -> implementation -> bitstream -> 云平台验收
```

修改程序后必须重新生成 Verilog 并重新综合。`$readmemh` 路径和内容在前述阶段进入工程，仅重跑 implementation 不会自动更新程序。

完成 lab2 后再把验证过的板级约定用于 lab3/lab4；流水线和总线时序需另行评估，不默认直接套用单周期 CPU 的写脉冲方案。
