# TODO

## lab3：解耦寄存器堆读地址与源寄存器依赖信息（暂缓）

- [ ] 后续优化 CPU 时序时再评估此项；当前保留 `InstructionDecode` 中将无 rs1 的指令读地址置零的逻辑，暂不修改 CPU 实现。

涉及实现：`lab3/src/main/scala/riscv/core/fivestage_final/`。

### 当前行为与不能直接移除的原因

`InstructionDecode.scala` 对 LUI、AUIPC、JAL 将 `regs_reg1_read_address` 置为 0，其余指令使用 `instruction(19, 15)`。该信号经 `CPU.scala` 同时用于寄存器堆读取、Control 冒险检测、ID Forwarding，并经 ID2EX 传入 EX Forwarding。置零同时表示“无 rs1 依赖”和“读取数值 0”，不能直接改为原始指令字段。

- **LUI 会直接计算错误。** 当前 LUI 的 ALU 操作数 1 仍选择寄存器数据，ALU 执行加法，依靠读取 x0 实现 `0 + immediate`。例如，若 x5 为 7，`lui x6, 0x28`（编码 `0x00028337`，其 `[19:15]` 为 5）应得到 `0x00028000`；直接使用原始字段读取会得到 `0x00028007`。仅屏蔽冒险和前递判断不足以修复这个问题。
- **Forwarding 会产生虚假匹配。** 无 rs1 指令的立即数字段可能与 MEM/WB 的目的寄存器相同，导致选择无关的前递数据。LUI 会实际使用该数据，产生错误结果；AUIPC、JAL 的对应寄存器操作数未用于有效结果计算，因此这类匹配本身不会直接破坏其计算结果或跳转目标。
- **Control 会产生虚假停顿。** LUI、AUIPC 可能错误触发与 EX 阶段 load 的 load-use 检测；JAL 可能错误触发与 EX 目的寄存器或 MEM 阶段 load 的依赖检测。结果为保持 PC、保持 IF2ID，并清空 ID2EX 插入气泡。正常执行时通常只损失性能，指令仍保留在 ID 等待重试。
- **停顿与中断入口存在额外交互（已于 2026-11-06 修复协调）。** 原实现中，`InstructionFetch.scala` 在停顿时不接受跳转，但 CLINT 仍可能提交中断 CSR 更新，导致 PC 未进入处理程序、MIE 却已清零。当前通过 `CLINT.stall_flag_ctrl` 延迟中断接受，直到 PC 能接受对应重定向；源寄存器依赖信息的解耦优化仍暂缓。

### 后续优化需要配套处理

- [ ] 区分寄存器堆的物理读地址与指令语义上的源寄存器依赖。若物理读地址直接使用原始字段，Control / Forwarding 应使用 `usesRs1` 或独立屏蔽后的源寄存器地址，并将所需信息传到 EX。
- [ ] 为 LUI 显式提供零操作数，或直接生成立即数结果，消除其对“读地址置零”的数据通路依赖。
- [ ] 按指令语义处理 CSR 立即数指令：其 `[19:15]` 是 `uimm`，不应作为通用寄存器依赖。当前实现未对这类指令屏蔽 rs1，仍可能产生虚假停顿；实际 CSR 立即数操作数应继续取自指令字段。
- [x] 修复当前五级流水中的中断接受、PC 重定向、IF2ID 冲刷与 CSR 提交的协调，并验证中断与 load-use 停顿同周期的情况。
- [ ] 实施修改时验证 LUI 非零原始字段、MEM/WB 前递匹配、LUI/AUIPC/JAL 虚假依赖、CSR 立即数，以及停顿与中断同周期发生等场景，并重新测量时序收益。

## lab3：中断重定向时冲刷 IF2ID 中的错误路径指令

- [x] 修复中断入口重定向、IF2ID 冲刷及停顿之间的协调，防止被中断程序中残留的分支覆盖中断入口跳转。

2026-11-06 在 cloud_pynq CPU /4（10 MHz）、正式 `say_goodbye` ROM 的 UART 引脚仿真中复现：

- 主时钟第 400015 拍，Timer 中断令 PC 从 `0x1308` 转向 `mtvec = 0x1074`，并清除 MIE。
- IF2ID 随后仍保留主程序 `0x1308` 处的分支指令 `0xf406c6e3`；该分支在等待操作数后将 PC 拉回 `0x1254`，中断处理程序没有正常执行，MIE 也没有恢复。
- 运行 120,000,000 个主时钟周期（三秒）后，UART 仍无输出。

相关代码为 `fivestage_final/Control.scala` 的普通指令处理分支、`InstructionDecode.scala` 的中断跳转合并与 `InstructionFetch.scala` 的 PC 更新。此复现与上文“停顿阻止入口跳转但 CSR 已提交”的问题不同，本次分别处理。

修复内容：普通指令触发 trap/mret 重定向时也冲刷 IF2ID；CLINT 在 `pc_stall` 时不接受 trap/mret、不提交中断状态。`misc/lab3_ans.yaml` 的 Control 参考答案已同步。新增 `InterruptRedirectTest`，覆盖 load-use 停顿期间的一拍中断请求、残留分支冲刷、连续两次中断返回和 mret 后错误路径指令冲刷；lab3 共 25 项测试通过，正式 `say_goodbye` ROM 的播放/暂停/继续/重播 UART 仿真通过。

修复前日志保留在 [UART 仿真](target/cpbits/l3bye/native.log) 和 [中断流水线跟踪](target/cpbits/l3bye/debug_irq.log)，修复后日志见新比特流交付目录。
