# CG-FPGA 云平台资源包（Vivado 2022.1）

面向希冀（educg）云 FPGA 平台的计算机组成原理 / RISC-V CPU 实验资源包。
本包中的 `cg_fpga` 封装 IP 已从 **Vivado 2022.2** 格式适配到 **Vivado 2022.1**。

## 版本信息

| 项目 | 版本 / 说明 |
|---|---|
| `cg_fpga` IP | v1.4，Vendor `educg.net` |
| 目标 Vivado | **2022.1** |
| 原始 IP 打包工具 | Vivado 2022.2（`component.xml` → `xilinxVersion 2022.2`） |
| PYNQ-Z2 board files | AMD XilinxBoardStore，TUL `pynq-z2` A.0（Rev 1.0） |
| 示例工程 | `design_1`：`cg_fpga_0` + `led_flow_0`（32 位跑马灯） |

## 目录结构

```
cg-fpga-vivado2022.1/
├── README.md                     ← 本文件
├── CHANGELOG.md                  ← 变更记录
├── MANIFEST.md                   ← 文件清单 / 校验和
├── SHA256SUMS.txt
├── .gitignore                    ← 忽略 Vivado 构建产物
├── ip_repo/                      ← 已适配 Vivado 2022.1 的 cg_fpga 1.4
│   ├── component.xml
│   ├── src/
│   ├── sim/
│   └── xgui/
├── board_files/                  ← PYNQ-Z2 Vivado board files
│   ├── README.md
│   └── pynq-z2/A.0/
├── docs/
│   ├── CG-FPGA-Pin-Map-v1.pdf    ← 云平台引脚 / 外设说明书
│   ├── exp1-guidebook.pdf        ← 实验一 LED 跑马灯指导书
│   ├── cg_fpga_port_map.md       ← cg_fpga 端口与协议速查
│   └── riscv_cpu_integration.md  ← RISC-V CPU 集成指南
├── example_project_1/            ← 可直接打开的示例 Vivado 工程
│   ├── project_1.xpr
│   ├── design_1.tcl
│   └── project_1.srcs/
├── tools/
│   └── patch_ip_repo_for_vivado_2022_1.py
└── upstream/
    ├── README.md
    └── ip_repo_v2022.2_original.zip   ← 原始 2022.2 包（仅作对照）
```

## 快速开始

### 1. 安装 board files

把 `board_files/pynq-z2` 复制到 Vivado 的 board files 目录：

**Vivado 2018.x ~ 2022.x**

```
<Vivado 安装目录>\data\boards\board_files\pynq-z2\A.0\board.xml
```

**Vivado 2023.x 及以后**

```
<Vivado 安装目录>\data\xhub\boards\XilinxBoardStore\boards\TUL\pynq-z2\A.0\
```

重启 Vivado，在 `Create Project → Boards` 中搜索 `pynq-z2`，Vendor 为 `tul.com.tw` 即成功。

### 2. 添加 IP 仓库

Vivado 中：

```
Settings → IP → Repository → + → 选择本包的 ip_repo 目录
```

或 Tcl：

```tcl
set_property ip_repo_paths {<本包路径>/ip_repo} [current_project]
update_ip_catalog
```

### 3. 打开示例工程

直接打开 `example_project_1/project_1.xpr`。
工程内的 `design_1` 已经包含：

- `cg_fpga_0`（云平台封装 IP）
- `led_flow_0`（32 位 LED 跑马灯）

连接关系：

```
cg_fpga_0/clk_100M   → led_flow_0/clk
cg_fpga_0/btn_rst    → led_flow_0/rst_n
led_flow_0/led[31:0] → cg_fpga_0/gpio_led[31:0]
```

打开后执行 **Reset Output Products → Generate Output Products**，再综合即可。

## cg_fpga 端口速查

完整说明见 [`docs/cg_fpga_port_map.md`](docs/cg_fpga_port_map.md)。

| 端口 | 方向（相对 cg_fpga） | 说明 |
|---|---|---|
| `clk_100M` | 输出 | 100 MHz 时钟，给用户逻辑 |
| `btn_rst` | 输出 | RST 按钮，低有效 |
| `btn_clk` | 输出 | CLK 按钮，低有效 |
| `btn[7:0]` | 输出 | 8 个按钮，低有效 |
| `gpio_sw_1/2[31:0]` | 输出 | 拨码开关，低有效 |
| `gpio_led[31:0]` | 输入 | 32 个 LED，低电平点亮 |
| `seg_cs[3:0]` / `seg_data[7:0]` | 输入 | 8 位数码管，共阴极 |
| `ledm_cs[5:0]` / `ledm_data[15:0]` | 输入 | 4×16×16 LED 矩阵，行列扫描 |
| `audio[6:0]` | 输入 | 7 个音调，低有效 |
| `vid_*` | 输入 | 视频窗口 |
| `rx_0` | 输入 | 内部 AXI Uartlite RX，接 CPU 的 `uart_tx` |
| `tx_0` | **输出** | 内部 AXI Uartlite TX，接 CPU 的 `uart_rx` |
| `DDR_*` / `FIXED_IO` | inout | 内部 PS7 的 DDR / MIO |

## 迁移到 RISC-V CPU 仓库

建议把本包作为 CPU 仓库的 `third_party/cg_fpga/` 或 `platform/` 子目录：

```
riscv-cpu-teaching/
├── src/                      ← CPU 的 Chisel / Verilog
├── platform/cg_fpga/
│   ├── ip_repo/
│   ├── board_files/
│   └── docs/
└── vivado/
    └── cpu_soc.xpr
```

集成步骤、BD 连线、MMIO 地址建议、UART 接法详见
[`docs/riscv_cpu_integration.md`](docs/riscv_cpu_integration.md)。

### 三个必须知道的要点

1. **UART 需要 TX/RX 交叉**
   `tx_0` 是内部 AXI Uartlite 的 TX 输出，接 CPU 的 `uart_rx`；
   `rx_0` 是内部 AXI Uartlite 的 RX 输入，接 CPU 的 `uart_tx`。
   - CPU 不能驱动 `tx_0`，否则多驱动；
   - CPU 的 `uart_rx` 不能接 `rx_0`，`rx_0` 不是网页数据输出；
   - 网页/PS 位于 Uartlite 的 AXI 侧，CPU 通过该 Uartlite 与网页双向通信；
   - 不需要“CPU 版” `cg_fpga`，也不需要删除内部 Uartlite；
   - 接入 CPU 前，可先把 `tx_0` 与 `rx_0` 短接，验证网页串口通路。
2. **PL CPU 不能直接访问 DDR**
   `cg_fpga` 内部的 DDR 只连到它自己的 PS7，封装 IP 没有向外暴露 AXI 主/从端口。
   简易 RISC-V CPU 请使用 BRAM 作为指令/数据存储器。
3. **补丁是版本相关的**
   如果重新解压 `upstream/ip_repo_v2022.2_original.zip`，需要重新执行
   `tools/patch_ip_repo_for_vivado_2022_1.py`；如果改用 Vivado 2022.2，则直接用原始包即可。

## 校验

```bash
# 确认没有 2022.2 专有字段
grep -r '"cell_name"[[:space:]]*:' ip_repo/src || echo "OK: no cell_name"
grep -r '"enabled"[[:space:]]*:' ip_repo/src || echo "OK: no enabled key"

# 校验文件完整性
sha256sum -c SHA256SUMS.txt
```

Windows PowerShell：

```powershell
Select-String -Path (Get-ChildItem ip_repo\src -Recurse -Filter *.xci) `
  -Pattern '"cell_name"\s*:|"enabled"\s*:'
# 无输出即正常

Get-Content SHA256SUMS.txt | ForEach-Object {
  $h, $p = $_ -split '  ', 2
  if ((Get-FileHash $p -Algorithm SHA256).Hash.ToLower() -ne $h) { "FAIL $p" }
}
```

## 来源与使用范围

- `cg_fpga` IP：educg.net（希冀平台课程提供）
- PYNQ-Z2 board files：AMD/Xilinx `XilinxBoardStore` → `boards/TUL/pynq-z2`
- `CG-FPGA-Pin-Map-v1.pdf`、`exp1-guidebook.pdf`：课程实验资料

本资源包仅供课程教学与实验使用，请勿对外分发课程资料。
