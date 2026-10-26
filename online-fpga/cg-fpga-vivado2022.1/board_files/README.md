# PYNQ-Z2 Vivado Board Files

来源：AMD/Xilinx 官方 Board Store（TUL 提交的原始板卡定义）
<https://github.com/Xilinx/XilinxBoardStore/tree/2022.2/boards/TUL/pynq-z2/A.0>

## 内容

```
pynq-z2/
└── A.0/
    ├── board.xml          # 板卡定义（板名 pynq-z2，vendor tul.com.tw）
    ├── part0_pins.xml     # 器件引脚映射
    ├── preset.xml         # Zynq PS 预设配置（DDR、时钟、外设）
    ├── pynq_z2.jpg        # 板卡图片
    └── xitem.json         # Board Store 元数据
```

- 目标器件：`xc7z020clg400-1`（XC7Z020-1CLG400C）
- Board name：`pynq-z2`，Vendor：`tul.com.tw`
- Board file 版本：A.0（Revision 1.0）

## 安装（Vivado 2018.x ~ 2022.x）

把 `pynq-z2` 整个文件夹复制到：

```
<Xilinx 安装目录>\Vivado\<版本>\data\boards\board_files\
```

最终路径应为：

```
...\Vivado\2022.1\data\boards\board_files\pynq-z2\A.0\board.xml
```

## 安装（Vivado 2023.x 及以后，新版 Board Store 路径）

新版 Vivado 从 `xhub/boards/XilinxBoardStore` 读取板卡：

```
<Xilinx 安装目录>\Vivado\<版本>\data\xhub\boards\XilinxBoardStore\boards\TUL\pynq-z2\A.0\
```

> 若该目录不存在，可先创建。也可以直接把 `pynq-z2` 放到旧路径
> `data\boards\board_files\`，Vivado 仍会扫描。

## 验证

1. 重启 Vivado（已打开的话必须重启才能重新扫描板卡）
2. `Create Project` → `Boards` 选项卡
3. 搜索 `pynq-z2`，Vendor 显示 `tul.com.tw` 即安装成功
4. 选择后 Part 会自动填为 `xc7z020clg400-1`

## 在本资源包中的用途

`cg_fpga` 的 BD 需要一个 `pynq-z2` 板卡工程作为载体。安装本 board file 后，
新建工程时选择 `pynq-z2`，Vivado 会自动填好：

- Part：`xc7z020clg400-1`
- PS7 预设：DDR3、MIO、时钟等

## 说明

- 该 board file 只负责 Vivado 工程中的 PS 配置（DDR3、MIO、时钟等），
  不包含 PYNQ 的 base overlay 设计源码。
- base overlay 的 Vivado 工程源码在：
  <https://github.com/Xilinx/PYNQ/tree/master/boards/Pynq-Z2>
  （克隆后 `boards/Pynq-Z2/base` 下执行 `make` 或用 Tcl 重建）
- 引脚约束（XDC）在同一个 base overlay 工程目录中，PYNQ 仓库里也有现成的
  `base.xdc` 可参考。
