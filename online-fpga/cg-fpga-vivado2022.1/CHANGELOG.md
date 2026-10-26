# Changelog

## 2026-10-26 — UART 接法文档修正

- 修正 `rx_0` / `tx_0` 的方向说明：二者是内部 AXI Uartlite 的 RX/TX，
  不是网页侧的 RX/TX。
- CPU 侧应交叉连接：`cg_fpga.tx_0 → CPU UART RX`，
  `cg_fpga.rx_0 ← CPU UART TX`。
- 删除“需要 CPU 版 cg_fpga 才能发送 UART”的错误结论；现有 v1.4 IP
  可直接用于双向 UART。
- 同步更新 `README.md`、`docs/cg_fpga_port_map.md`、
  `docs/riscv_cpu_integration.md`。

## 2026-10-26 — 资源包整理

- 新增 `dist/cg-fpga-vivado2022.1/` 资源包，用于迁移到教学用 RISC-V CPU 仓库。
- 收录已适配 Vivado 2022.1 的 `cg_fpga` v1.4 IP 仓库。
- 收录 PYNQ-Z2 Vivado board files（TUL pynq-z2 A.0）。
- 收录平台引脚说明书、实验一指导书。
- 收录示例工程 `example_project_1`（`cg_fpga_0` + `led_flow_0`）。
- 新增端口速查、RISC-V CPU 集成指南、迁移说明。

## 2026-10-23 — cg_fpga 适配 Vivado 2022.1

原始 `ip_repo` 由 Vivado 2022.2 打包，Vivado 2022.1 在生成输出产物时报：

```
ERROR: [IP_Flow 19-8166] Failed to verify json document against the schema
'xilinx.com:schema:json_instance:1.0'
  additionalProperties: { disallowed: "cell_name", ... }

ERROR: [IP_Flow 19-8166] ... disallowed: "enabled"
```

处理：

1. 删除 24 个 `.xci` 中的 `ip_inst.cell_name` 字段；
2. 删除参数值对象中的 `enabled` 字段；
3. 清理 `project_1.gen/.../design_1_cg_fpga_0_0`、`project_1.cache/ip`、
   `project_1.runs/synth_1`、`project_1.runs/impl_1`；
4. 重新 Generate Output Products，Vivado 2022.1 自动把子核 revision
   29→28、31→30、15→14 等归一化；
5. 综合通过：`0 errors, 0 critical warnings`。

补丁不改变 IP 的功能，只删除 2022.2 新增的元数据键。
原始 2022.2 包保留在 `upstream/ip_repo_v2022.2_original.zip`。
