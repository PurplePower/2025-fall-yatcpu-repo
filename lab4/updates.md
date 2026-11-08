# lab4 cloud_pynq 迁移记录

日期：2026-11-06。

当前版本已移除初版的 CPU /4 分频，CPU、AXI、内存和外设直接运行在 40 MHz。
本次参照 lab1–lab3 的迁移记录，为增加 AXI4Lite 总线的 lab4 接入 cg_fpga
云端 PYNQ-Z2。目录名称沿用 `cloud_pynq`，对应用户所指的云 FPGA 平台。
教学仓库保留 AXI 学生填空框架；功能测试与比特流使用本地参考答案填充后的实现。

提交摘要（2026-11-08）：

- 接入 cloud_pynq 顶层、AXI 外设适配和 Vivado 构建流程，CPU 与总线共用 40 MHz 时钟。
- 修复 AXI 停顿期间的分支、trap/mret 和 Timer 中断处理，以及 UART 单次读写语义。
- 完善 C 启动、链接与跨平台构建，新增 π、显示及验证程序，更新歌词播放与控制。
- 增加 AXI 回归和三套正式 ROM 原生仿真；保留学生填空框架与本地参考答案的边界。
- 提交源码、脚本、文档和资源固件；已生成的两份比特流、报告、源码快照与工程缓存
  继续按现有忽略规则保存在本地，不纳入 Git。

提交前复核：学生 AXI 框架与原版本逐字节一致，`sbt "Test / compile"` 通过；
两套交付各 25 个文件及各 103 项源码快照哈希校验通过，交付固件与资源文件一致。
当前源码与构建快照的唯一区别为已恢复的 AXI 学生填空框架。

## 1. 平台与 AXI 连接

新增 `src/main/scala/board/cloud_pynq/Top.scala`：

- 采用 lab3 的平台端口、UART 交叉连接、数码管、LED 矩阵和复位接口。
- 平台输入 100 MHz，clk_wiz 输出 40 MHz，Top 不再对 CPU 分频。
- CPU、AXI 主从设备、ROMLoader、32 KiB BRAM、UART、Timer、显示和 MTime
  共享 40 MHz 时钟；AXI 无跨时钟域通路。
- CPU 的取指和数据访问全部经 AXI4Lite，总线停顿保留课程实现；不使用 lab3 的
  指令 ROM 直取或组合读 AsyncMemory。
- ROMLoader 经 AXI 从 `0x1000` 装载完整固件，最后一次写响应完成后才释放 CPU 复位。
- 同一时钟域中的两级复位同步器异步置位、同步释放；额外同步寄存后的 `systemReset` 才驱动
  AXI/BRAM 域，消除 BRAM 的 `REQP-1839` 异步控制警告。
- UART TX 在复位期间保持高电平，避免复位时产生伪字符。
- 音频和视频保持关闭，未接入 VRAM。

MMIO 与 lab3 云平台一致：

| 地址 | 外设 |
|---|---|
| `0x00000000` | 32 KiB RAM |
| `0x40000000` | UART |
| `0x60000000` | 数码管 |
| `0x80000000` | Timer |
| `0xa0000000` | LED 矩阵 |
| `0xc0000000` | MTime，低/高 32 位偏移分别为 0/4 |

新增 `RAMBundle`、`AXIMMIO`，复用 lab3 的 `SegDisplay`、`LedMatrixDisplay`
与 `MTime`。适配器屏蔽设备号地址位，将从设备保持多拍的 write 转为一次操作，
并对读数据取稳定快照。MTime 为 1 MHz，UART 为 9600、8-N-1。

UART 保留 lab4 的 AXI 接口，升级为 lab3 的串口引擎及 TX ready / RX valid
状态寄存器；仅接收寄存器的实际 AXI read 清除接收状态，每次 AXI store 只发送一次。
本地参考答案另补上 `!RVALID` 条件，防止等待 RREADY 时重写已有读响应。

## 2. 总线停顿与中断修复

- `Control.scala` 新增与跳转结果无关的 `redirect_stall`，在 MEM 等待响应或 ID
  操作数尚未就绪时禁止重定向；普通指令触发的 trap/mret 同样冲刷 IF2ID。
- `CPU.scala` 用 `redirect_stall` 限定送入取指单元的跳转，避免分支在数据冒险
  或较早的访存仍未完成时提前修改 PC。
- `CLINT.scala` 等待相同停顿条件解除后才提交中断/返回与 CSR 更新。
- Timer 上升沿进入单比特 pending 状态，跨 AXI 停顿保存；接受后清除，持续高电平
  不重复入队。UART 仍使用外设保持到读取接收寄存器的中断电平。
- 中断输入直接来自外设，避免 IF2ID 冲刷丢失事件。

仍使用课程简化中断语义，没有扩展为完整 RISC-V 特权架构；Timer pending
只保存一个事件，多个未处理周期会合并。

## 3. C 固件与构建

同步 lab3 的 `init.S`、`link.lds`、`mmio.h` 和 GCC/CMake 构建链：

- `rv32i_zicsr` / `ilp32`，不依赖硬件 M 扩展。
- 程序从 `0x1000` 开始，BSS 从 `0x6000` 开始，栈顶为 `0x8000`。
- 启动清零 BSS，收集 `.srodata*` / `.sbss*`，并检查内存和栈空间。
- trap 上下文正确保存/恢复 s0，修正默认 trap_handler 符号。
- 增加 `pi.c`、`led_control_demo.c`、`simpletest.c`、`trap_registers.S`。
- `say_goodbye.c` 同步为 Timer 中断计时、UART 非阻塞发送及 s/p/r 控制版本。
- `pi.c` 默认计算 200 位，输出 20 位小数，以 1 MHz MTime 计时；软件乘除实现
  已反汇编核对，不含 mul/div/rem 指令。
- 所有默认 C/汇编目标重新构建成功，固件复制到 `src/main/resources/`。

构建入口为 `csrc/build.bat` / `csrc/build.sh` 或 `make cloud_pynq PROGRAM=pi`。
完整命令见 [平台 README](vivado/cloud_pynq/README.md)。

## 4. 测试与学生框架

- 填入参考答案后 `sbt test`：**16/16 通过**，共 9 个 suite。
- 新增 `CloudAXITest`：覆盖 Timer 脉冲跨停顿保存、持续电平不重复触发、mret
  等待访存、trap 冲刷、分支停顿和 AXI 响应背压下稳定读/单次写。
- 原有取指/访存、1000 次随机总线事务、ROMLoader、Timer、流水寄存器、递归
  Fibonacci、quicksort、字节访问与实体板顶层测试通过。
- 正式 `say_goodbye` UART 引脚仿真：歌词与 CRLF 正确，两行起始间隔
  **40,003,200 个 40 MHz 周期**；一次播放后停止、暂停/继续、重复暂停、忽略无效键、
  播放结束及发送期间重播全部通过。
- 正式 `pi` UART 引脚仿真输出 `3.14159265358979323846`，计算 200 位，
  `cycles = 768193490`，`elapsed_us = 19204837`，即 **19204.837 ms**；
  cycle CSR 与 MTime 的 40:1 比例检查通过。
- 正式 LED ROM 仿真：验证全部 8 位数码管与 64 行矩阵、每秒样式变化、UART
  中断单字节回显，以及复位重新装载并恢复初始显示。
- `verilog/verify_cloud_pynq.sh` / `make native-cloud-test` 可重跑三组正式 ROM 仿真；
  RTL 和日志写入隔离的 `target/` 目录，不覆盖 Vivado 使用的 RTL。
- 默认不生成 VCD，可通过 `WRITE_VCD` 环境变量启用。

提交前恢复 `bus/AXI4Lite.scala`，与原学生框架逐字节一致。本地
`misc/lab4_ans_basic.yaml` 经填充器回填后与实际测试实现一致，仍由 Git 忽略。
恢复框架后 `sbt "Test / compile"` 通过。已完成填空的源码与实际生成 RTL
归档在比特流的 `source_snapshot.zip` 中，不纳入源码提交。

## 5. Vivado 与交付

新增 `vivado/cloud_pynq` 的项目、生成、收尾检查、时钟约束及 README，并保留
2020.1 / cg_fpga 1.6.3 的可选入口。本次使用 **Vivado 2022.1、cg_fpga 1.4、
xc7z020clg400-1**。

`clocks.xdc` 仅设置主时钟域的复位同步器 `ASYNC_REG`。CPU、AXI 与全部外设
直接受时钟 IP 提供的 40 MHz（25 ns）约束，不存在 `cloud_cpu` 或分频寄存器。
收尾脚本会检查这两点；没有放宽时钟或添加额外 false path。
当前无分频版本在独立 `cloud_pynq40_pi` / `cloud_pynq40_say_goodbye` 工程中，
按 40 MHz 约束重新综合并完整布局布线，不复用原 10 MHz 实现的 checkpoint。
最终报告与交付清单随两份比特流归档。

最终完成时间为 2026-11-06 21:03（UTC+8）：

| 程序 | 比特流 | WNS | WHS | WPWS |
|---|---|---:|---:|---:|
| pi | [cloud_pynq_pi.bit](vivado/cloud_pynq/bitstreams/pi/cloud_pynq_pi.bit) | +1.064 ns | +0.010 ns | +3.000 ns |
| say_goodbye | [cloud_pynq_say_goodbye.bit](vivado/cloud_pynq/bitstreams/say_goodbye/cloud_pynq_say_goodbye.bit) | +1.165 ns | +0.027 ns | +3.000 ns |

两份比特流均为 4,045,676 字节，setup/hold/pulse-width 违例端点均为 0，
TNS/THS/TPWS 均为 0，`check_timing` 的 12 个类别全部为 0。
DRC 无 Error / Critical Warning，无 BRAM `REQP-1839`；剩余均为 cg_fpga IP 项目：
`PDCN-1569` 3 项 warning、`RTSTAT-10` 1 项 warning、`REQP-165` 1 项 advisory、
`REQP-181` 2 项 advisory。没有通过降级或屏蔽 DRC 规则消除警告。

SHA-256：

```text
pi:
4d1ef9b0aad2997c73fbfaf9e6ceac62c96b35b3ebd6caeb42f7d124c65487ed
say_goodbye:
6b984c534b2dfc5c712bf605912ba6dbe2dfb776024d39a99361bf63dc89dac6
```

每个 `vivado/cloud_pynq/bitstreams/<程序名>/` 包含比特流、固件、构建清单、
SHA-256、完成填空后的源码快照与源码哈希、精确 RTL、正式 ROM 仿真源码及日志、
16 项回归记录、恢复框架后的编译记录、最终时序/DRC/利用率/时钟报告和 routed DCP。
两个目录的完整文件包为
[cloud_pynq_lab4_20261106.zip](vivado/cloud_pynq/bitstreams/cloud_pynq_lab4_20261106.zip)。

生成 RTL、C 构建缓存、Vivado 工程和比特流均由 `.gitignore` 忽略；本次 Git 提交包含
迁移源码、构建与验证脚本、文档及 `src/main/resources/` 中的固件。

云平台网页实际烧录尚未验证。


## 6. 去除 CPU 分频（2026-11-06）

Lab4 的 AXI4Lite 已提供握手和事务完成语义，指令/数据访问由总线状态机等待响应，
不需要通过降低 CPU 相对内存的频率来等待数据。参照 lab4 的 z710 / z710v1.3，
本次移除 cloud_pynq 的 `CPU_clkdiv` 和额外 CPU 复位时钟域，CPU、AXI、BRAM、
ROMLoader、UART、Timer、数码管、矩阵与 MTime 全部直接使用 40 MHz 输入。

保留单一时钟域中的异步置位/同步释放复位同步器及 BRAM 前的同步复位寄存，
仍在 ROMLoader 的最后一个 AXI 写响应完成后才释放 CPU。

- `say_goodbye.c`：`GOODBYE_TIMER_TICKS` 从 100000 改为 400000，保持 10 ms 节拍。
- `led_control_demo.c`：`DEMO_TIMER_TICKS` 从 10000000 改为 40000000，保持 1 秒动画。
- MTime 按实际 40 MHz 配置，仍为 1 MHz；UART 按实际时钟配置，仍为 9600 8-N-1。
- 原生 π 验证改为检查 cycle CSR / MTime 的 40:1 比例。
- 去掉 XDC 的分频时钟约束及收尾脚本对 `cloud_cpu` 的要求，改为检查 25 ns 主时钟，
  并拒绝包含旧分频时钟或寄存器的工程。
- 初版 10 MHz 的完整交付包留存在 `target/previous-clock10/`；当前交付目录更新为
  无分频版本。


去除分频后的 `sbt test` 仍为 16/16 通过；三个正式 ROM 的原生仿真全部通过。
π 的 200 位计算耗时从 76818.977 ms 降至 19204.837 ms，约为原来的四分之一；
CPU 周期数为 768193490，cycle/MTime 比例为 40:1。歌词两行起始间隔为
40003200 个主时钟周期（约 1.000080 秒），暂停、继续、无效按键与两种重播场景通过。
学生 AXI 填空框架再次逐字节恢复，完整实现归档在新交付的源码快照中。

无分频版本已于 21:03 完成两份比特流生成，最终时序、DRC、时钟报告、源码快照与
SHA-256 均已更新到第 5 节链接的交付目录。所有 `check_timing` 类别仍为 0，
CPU/AXI 域的实际时钟为 40 MHz，没有 `cloud_cpu` 时钟或 `CPU_clkdiv` 寄存器；
平台的 100 MHz 参考时钟与 MMCM 反馈时钟仍正常保留。
