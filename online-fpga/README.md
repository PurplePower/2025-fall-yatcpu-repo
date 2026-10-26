# dist — CG-FPGA 资源包

本目录是可直接迁移到教学用 RISC-V CPU 仓库的发布包。

## 发布物

| 文件 | 大小 | SHA256 |
|---|---:|---|
| `cg-fpga-vivado2022.1.zip` | 4.01 MB | `9e3fa5d87e67c996960e5c2f97178f4fbeb175d38ddbdd38e50d3def10876e7a` |
| `cg-fpga-vivado2022.1/` | 5.91 MB | 解压后的目录，内容与 zip 一致 |

## 包含内容

- `ip_repo/`：已适配 **Vivado 2022.1** 的 `cg_fpga` v1.4 封装 IP
- `board_files/`：PYNQ-Z2 Vivado board files（TUL `pynq-z2` A.0）
- `docs/`：平台引脚说明书、实验一指导书、端口速查、RISC-V CPU 集成指南
- `example_project_1/`：`cg_fpga_0` + `led_flow_0` 示例工程
- `tools/`：IP 补丁脚本（Vivado 2022.2 → 2022.1）
- `upstream/`：原始 2022.2 IP 包（仅作对照）
- `MANIFEST.md` / `SHA256SUMS.txt`：文件清单与校验和

## 已验证

- `ip_repo` 24 个 `.xci` 中 `cell_name`、`enabled` 键均为 0
- 55 个文件 SHA256 校验通过
- 从 zip 解压后，用 Vivado 2022.1 打开 `example_project_1/project_1.xpr`
  并执行 Generate Output Products，成功（`cg_fpga_0` / `led_flow_0` 均生成）
- 原工程综合通过：0 errors / 0 critical warnings

> 说明：本目录中的 `cg-fpga-vivado2022.1/` 已根据 UART 接法修正文档，
> 文件级校验和以 `cg-fpga-vivado2022.1/SHA256SUMS.txt` 为准。
> 上表中 `cg-fpga-vivado2022.1.zip` 的 SHA256 对应修正前的原始打包文件；
> 如需重新打包，请重新生成该 zip 及其 SHA256。

## 使用

解压后进入 `cg-fpga-vivado2022.1/`，先阅读 `README.md`。
