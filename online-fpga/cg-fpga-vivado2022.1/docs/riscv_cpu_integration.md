# 把 RISC-V CPU 接到 cg_fpga

本文说明如何把 Chisel / Verilog 实现的 32 位 RISC-V CPU 作为 BD 模块接入云平台，
并使用 RST 按钮、LED 矩阵和 UART。

## 1. 总体结构

```
cg_fpga_0 (平台封装 IP)
   ├── clk_100M  ──────────────► cpu_top/clk
   ├── btn_rst   ──────────────► cpu_top/rst_n
   ├── btn[7:0]  ──────────────► cpu_top/btn
   ├── gpio_sw_* ──────────────► cpu_top/sw*
   ├── ledm_cs   ◄────────────── cpu_top/ledm_cs
   ├── ledm_data ◄────────────── cpu_top/ledm_data
   ├── gpio_led  ◄────────────── cpu_top/gpio_led
   └── rx_0      ──────────────► cpu_top/uart_rx
       tx_0      ──(内部Uartlite)──► 不能由 CPU 驱动
```

CPU 是 BD 里的一个 **Add Module**，与 `cg_fpga_0` 平级；不要把 CPU 放进 `cg_fpga` 内部。

## 2. CPU 顶层端口建议

```verilog
module cpu_top (
    input  wire        clk,        // cg_fpga/clk_100M
    input  wire        rst_n,      // cg_fpga/btn_rst, 低有效

    input  wire [7:0]  btn,        // 低有效
    input  wire [31:0] sw1,
    input  wire [31:0] sw2,

    output wire [5:0]  ledm_cs,
    output wire [15:0] ledm_data,
    output wire [31:0] gpio_led,   // 低电平点亮

    input  wire        uart_rx,    // cg_fpga/rx_0
    output wire        uart_tx     // 暂时不要接 tx_0
);
```

Chisel 顶层默认时钟/复位端口叫 `clock` / `reset`，建议用上面的 Verilog 包装一层：

```verilog
CpuTop u_cpu (
    .clock(clk),
    .reset(~rst_n),
    .io_btn(btn),
    ...
);
```

## 3. BD 连线

在 `design_1` 中 `Add Module` → 选择 `cpu_top`：

| cpu_top | cg_fpga_0 | 备注 |
|---|---|---|
| `clk` | `clk_100M` | 100 MHz |
| `rst_n` | `btn_rst` | 低有效 |
| `btn[7:0]` | `btn[7:0]` | 低有效 |
| `sw1[31:0]` | `gpio_sw_1[31:0]` | 低有效 |
| `sw2[31:0]` | `gpio_sw_2[31:0]` | 低有效 |
| `ledm_cs[5:0]` | `ledm_cs[5:0]` | CPU 输出 |
| `ledm_data[15:0]` | `ledm_data[15:0]` | CPU 输出，低电平点亮 |
| `gpio_led[31:0]` | `gpio_led[31:0]` | CPU 输出，低电平点亮 |
| `uart_rx` | `rx_0` | 可接 |
| `uart_tx` | `tx_0` | **不可直接接**，见第 6 节 |

连接完成后：

1. 右键 `design_1` → **Create HDL Wrapper**
2. 确认 `design_1_wrapper` 为顶层
3. **Reset Output Products → Generate Output Products**
4. Generate Bitstream

## 4. 建议的 MMIO 地址映射

CPU 用简单的 load/store 访问外设。下面是一套可直接采用的字节地址：

| 地址 | 读 | 写 |
|---|---|---|
| `0x0000_0000 ~ 0x0000_FFFF` | IMEM（BRAM） | — |
| `0x0001_0000 ~ 0x0001_FFFF` | DMEM（BRAM） | DMEM |
| `0x1000_0000` | UART RX data[7:0] | UART TX data[7:0] |
| `0x1000_0004` | bit0 = TX ready，bit1 = RX valid | — |
| `0x1000_1000 + row*4` | — | LED 矩阵第 `row` 行（低 16 位） |
| `0x1000_2000` | `btn[7:0]` | — |
| `0x1000_2004` | `gpio_sw_1` | — |
| `0x1000_2008` | `gpio_sw_2` | — |

片选建议：

```
addr[31:28] == 0x0  -> BRAM
addr[31:28] == 0x1  -> MMIO
```

## 5. RST 与 LED 矩阵

### 5.1 复位同步

`btn_rst` 是异步、低有效。建议：

```scala
class ResetSync extends Module {
  val io = IO(new Bundle {
    val rst_n_async = Input(Bool())
    val rst_n       = Output(Bool())
  })
  val sync = RegInit(0.U(2.W))
  sync := Cat(sync(0), io.rst_n_async)
  io.rst_n := sync(1)
}
```

### 5.2 LED 矩阵扫描

帧缓存 64 行 × 16 bit，扫描控制器独立运行：

```scala
class LedMatrixCtrl extends Module {
  val io = IO(new Bundle {
    val cs   = Output(UInt(6.W))
    val data = Output(UInt(16.W))
    val we    = Input(Bool())
    val addr  = Input(UInt(6.W))   // {panel[1:0], row[3:0]}
    val wdata = Input(UInt(16.W))
  })

  val fb = RegInit(VecInit(Seq.fill(64)(0.U(16.W))))
  when(io.we) { fb(io.addr) := io.wdata }

  val divMax = 20000 - 1           // 200 us @ 100 MHz
  val div    = RegInit(0.U(15.W))
  val scan   = RegInit(0.U(6.W))
  when(div === divMax.U) {
    div  := 0.U
    scan := scan + 1.U
  }.otherwise {
    div := div + 1.U
  }

  io.cs   := Cat(scan(5, 4), scan(3, 0))
  io.data := ~fb(scan)             // 低电平点亮
}
```

C 程序写一行：

```c
#define LEDM_BASE 0x10001000u
static inline void ledm_row(int panel, int row, unsigned short bits) {
    *(volatile unsigned int *)(LEDM_BASE + ((panel * 16 + row) << 2)) = bits;
}
```

> 列方向（`bit15` 对应最左还是最右）手册没有明确，先按一种接，上板后如果左右相反，
> 在控制器里把 `data` 的位序整体反转即可。

## 6. UART 的正确接法

### 6.1 参数

- 9600 bps，8 数据位，无校验，1 停止位
- 100 MHz 下分频系数：`100_000_000 / 9600 ≈ 10417`

### 6.2 RX

`UartRx.io.rx <- cg_fpga_0/rx_0` 可以直接接。
CPU 和内部 Uartlite 并行接收，互不影响。

### 6.3 TX

`tx_0` 是 `cg_fpga` 的**输出**，内部已由 `cg_fpga_axi_uartlite_0_0` 驱动：
`assign tx_0 = cg_axi_uartlite_0_tx;`

因此 **不能**把 CPU 的 `uart_tx` 直接接到 `tx_0`，否则是多驱动。

可选方案：

1. **向课程方索取 CPU 版 cg_fpga**（推荐）
   - 去掉内部 AXI Uartlite
   - 把 `tx_0` 改成输入，或新增 `uart_tx` / `uart_rx` 端口
   - 重新打包 IP
2. **只收不发**
   - CPU 只接 `rx_0`
   - 运行结果用 LED 矩阵或数码管显示
3. **修改封装 IP（进阶，不推荐课程实验使用）**
   - 编辑 `ip_repo/src/cg_fpga.v`
   - 删除 Uartlite 实例和 `assign tx_0 = ...`
   - 同步修改 `component.xml` 的端口方向并重新打包

### 6.4 C 程序输出

TX 可用时：

```c
#define UART_BASE 0x10000000u
#define UART_TX   (*(volatile unsigned int *)(UART_BASE + 0x0))
#define UART_STAT (*(volatile unsigned int *)(UART_BASE + 0x4))

void uart_putc(char c) {
    while ((UART_STAT & 1) == 0) { }
    UART_TX = (unsigned char)c;
}
```

重定向 newlib 的 `_write` 即可让 `printf` 走串口。

## 7. 存储器与 C 程序

`cg_fpga` 的 DDR 只连到内部 PS7，**没有向 PL 暴露 AXI 端口**。
因此 PL 侧的 CPU 不能直接访问 DDR，建议：

- IMEM / DMEM 使用 BRAM；
- 用 `$readmemh` 或 `.coe` 初始化程序；
- 程序随 bitstream 一起烧录，修改程序需要重新生成 bitstream；
- 如果需要在线加载程序，需要额外设计 UART/BRAM 加载器（TX 可用时）。

## 8. 集成检查清单

- [ ] `clk_100M` 已接 CPU 时钟，时序约束按 100 MHz
- [ ] `btn_rst` 经过复位同步后再接 CPU
- [ ] `ledm_cs` / `ledm_data` 位宽和方向正确
- [ ] LED 矩阵扫描周期约 200 µs/行
- [ ] UART RX 接 `rx_0`
- [ ] UART TX 未直接接 `tx_0`（或已换成 CPU 版 cg_fpga）
- [ ] CPU 使用 BRAM，未假设可访问 DDR
- [ ] Vivado 2022.1 使用本包 `ip_repo`；2022.2 使用 `upstream/` 原始包
