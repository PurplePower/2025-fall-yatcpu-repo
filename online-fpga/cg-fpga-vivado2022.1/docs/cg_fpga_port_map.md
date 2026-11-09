# cg_fpga 端口与协议速查

`cg_fpga` 是云 FPGA 平台的封装 IP（v1.4，Vendor `educg.net`）。
它内部包含一个 Zynq PS7（DDR / MIO）和板载外设的 AXI 控制器。
用户逻辑（CPU）作为独立的 BD 模块，与 `cg_fpga` 平级连接。

> 方向说明：下表的“方向”是**相对 `cg_fpga`** 而言。
> `输出` = `cg_fpga` 驱动给用户逻辑；`输入` = 用户逻辑驱动给 `cg_fpga`。
>
> 特别注意：UART 的 `rx_0` / `tx_0` 是按**内部 AXI Uartlite** 命名的，
> 不是按网页侧命名的。CPU 侧必须做 TX/RX 交叉，见 §4。

## 1. 时钟与复位

| 端口 | 方向 | 位宽 | 说明 |
|---|---|---|---|
| `clk_100M` | 输出 | 1 | 100 MHz 系统时钟（来自内部 PS7 FCLK0） |
| `btn_rst` | 输出 | 1 | RST 按钮，**低有效**（按下 = 0，松开 = 1） |
| `btn_clk` | 输出 | 1 | CLK 按钮，**低有效** |

建议：

- `clk_100M` 直接作为 CPU 时钟；
- `btn_rst` 做“异步复位、同步释放”后接 CPU 复位；
- `btn_clk` 可用于单步/手动时钟，不需要时忽略。

## 2. 输入设备

| 端口 | 方向 | 位宽 | 有效电平 | 说明 |
|---|---|---|---|---|
| `btn[7:0]` | 输出 | 8 | 低有效 | 8 个按钮，按下为 0 |
| `gpio_sw_1[31:0]` | 输出 | 32 | 低有效 | 拨码开关组 1 |
| `gpio_sw_2[31:0]` | 输出 | 32 | 低有效 | 拨码开关组 2 |

## 3. 输出设备

| 端口 | 方向 | 位宽 | 有效电平 | 说明 |
|---|---|---|---|---|
| `gpio_led[31:0]` | 输入 | 32 | 低电平点亮 | 32 个 LED，1 = 熄灭，0 = 点亮 |
| `seg_cs[3:0]` | 输入 | 4 | — | 8 位数码管片选 |
| `seg_data[7:0]` | 输入 | 8 | — | 数码管段码，共阴极 |
| `ledm_cs[5:0]` | 输入 | 6 | — | LED 矩阵片选 |
| `ledm_data[15:0]` | 输入 | 16 | 低电平点亮 | LED 矩阵行数据 |
| `audio[6:0]` | 输入 | 7 | 低有效 | 7 个音调，对应位写 0 发声 |
| `vid_*` | 输入 | — | 高有效 | 视频窗口 |

### 3.1 8 位数码管

- `seg_cs`：片选，值 `0~8` 从右到左对应每一位；一次只选一位。
- `seg_data`：段码，共阴极。
- 常用段码：

| 字符 | 段码 | 字符 | 段码 |
|---|---|---|---|
| 0 | `0x3F` | 8 | `0x7F` |
| 1 | `0x06` | 9 | `0x6F` |
| 2 | `0x5B` | A | `0x77` |
| 3 | `0x4F` | b | `0x7C` |
| 4 | `0x66` | C | `0x39` |
| 5 | `0x6D` | d | `0x5E` |
| 6 | `0x7D` | E | `0x79` |
| 7 | `0x07` | F | `0x71` |

### 3.2 LED 矩阵

- 4 块 **16×16** 矩阵，共 64 行 × 16 列。
- `ledm_cs[5:4]`：屏幕选择，从左到右 `00`、`01`、`10`、`11`。
- `ledm_cs[3:0]`：行选择，`0000` 是第一行，`1111` 是第 16 行。
- `ledm_data[15:0]`：当前行的 16 列数据，**低电平点亮**。

扫描伪代码：

```
for panel in 0..3:
  for row in 0..15:
     ledm_cs   = {panel[1:0], row[3:0]}
     ledm_data = ~framebuffer[panel][row]
     delay(200 us)
```

64 行 × 200 µs ≈ 12.8 ms/帧（约 78 Hz），不会明显闪烁。
CPU 只需周期性地写帧缓存，扫描由独立的硬件控制器完成。

全局行号与 `cs` 的关系：

```
global_row = panel * 16 + row        (0..63)
ledm_cs    = {panel[1:0], row[3:0]}
```

## 4. 串口

| 端口 | 方向（相对 cg_fpga） | 位宽 | 说明 |
|---|---|---|---|
| `rx_0` | 输入 | 1 | 内部 AXI Uartlite 的 RX：接 CPU 的 `uart_tx` |
| `tx_0` | **输出** | 1 | 内部 AXI Uartlite 的 TX：接 CPU 的 `uart_rx` |

参数（来自 `cg_fpga_axi_uartlite_0_0.xci`）：

| 参数 | 值 |
|---|---|
| 波特率 | 9600 |
| 数据位 | 8 |
| 校验 | 无 |
| 停止位 | 1（AXI Uartlite 固定） |
| 参考时钟 | 100 MHz |

### 正确接法：TX/RX 交叉

`cg_fpga` 内部已经这样连接：

```verilog
assign rx_0_1 = rx_0;
assign tx_0   = cg_axi_uartlite_0_tx;

cg_fpga_axi_uartlite_0_0 cg_axi_uartlite_0
     (.rx(rx_0_1), .tx(cg_axi_uartlite_0_tx), ...);
```

也就是说：

- `rx_0` 是 Uartlite 的 **RX 输入**，CPU 的 `uart_tx` 接这里；
- `tx_0` 是 Uartlite 的 **TX 输出**，CPU 的 `uart_rx` 接这里；
- 网页/PS 位于 Uartlite 的 AXI 侧，不在 `rx_0` / `tx_0` 这一侧。

CPU 侧必须交叉连接：

| CPU 侧 | cg_fpga 侧 | 数据方向 |
|---|---|---|
| `uart_rx` | `tx_0` | Uartlite TX → CPU RX |
| `uart_tx` | `rx_0` | CPU TX → Uartlite RX |

完整数据流：

```text
网页/PS7 → 写 Uartlite TX → cg_fpga.tx_0 → CPU UART RX
CPU UART TX → cg_fpga.rx_0 → Uartlite RX → PS7 读 → 网页
```

注意事项：

- **不要**把 CPU 的 `uart_tx` 接到 `tx_0`：`tx_0` 是 `cg_fpga` 的输出，会多驱动；
- **不要**把 CPU 的 `uart_rx` 接到 `rx_0`：`rx_0` 是 Uartlite 的输入，不是网页数据输出；
- 不需要“CPU 版” `cg_fpga`，也不需要删除内部 Uartlite；
- 前提是 PS 侧网页程序确实在读写这个 Uartlite。接入 CPU 前，可先把 `tx_0` 与 `rx_0` 短接，在网页串口输入字符验证通路。

## 5. PS7 / DDR

| 端口 | 方向 | 说明 |
|---|---|---|
| `DDR_*` | inout | 内部 PS7 的 DDR3 接口 |
| `FIXED_IO` | inout | 内部 PS7 的 MIO / 时钟 / 复位 |

`cg_fpga` 没有向外暴露 AXI 主/从端口，因此：

- 用户 CPU 不能直接访问 DDR；
- 简易 CPU 请使用 BRAM 作为指令/数据存储器；
- 如需 DDR，需要修改 `cg_fpga` 封装并新增 AXI 端口（不在本包范围内）。

## 6. 参考

- `CG-FPGA-Pin-Map-v1.pdf`（本包 `docs/`）
- `cg_fpga.v`（本包 `ip_repo/src/cg_fpga.v`）
- `cg_fpga_axi_uartlite_0_0.xci`（本包 `ip_repo/src/`）
