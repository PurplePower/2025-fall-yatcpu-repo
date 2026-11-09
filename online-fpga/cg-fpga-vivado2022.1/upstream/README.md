# upstream — 原始 2022.2 资源

`ip_repo_v2022.2_original.zip` 是课程提供的原始 `cg_fpga` v1.4 封装 IP，
由 **Vivado 2022.2** 打包。

## 什么时候用它

- 你使用 **Vivado 2022.2**：直接用这个原始包，不要用本包根目录的 `ip_repo/`
  （`ip_repo/` 是给 2022.1 打过补丁的版本）。
- 需要对比补丁改了什么：解压后与 `../ip_repo/` 逐文件比较。
- 需要重新打补丁：解压后运行
  `python ../tools/patch_ip_repo_for_vivado_2022_1.py <解压后的 ip_repo>`。

## 与 2022.1 版的差别

只删除两类 2022.2 专有元数据键：

| 键 | 位置 | 数量 |
|---|---|---|
| `cell_name` | `ip_inst` 下 | 24 处（每个 `.xci` 1 处） |
| `enabled` | `parameters/.../<param>/[0]` 下 | 406 处 |

不修改任何参数值、端口、地址或功能逻辑。

## 校验

原始包 SHA256 见 `../SHA256SUMS.txt` 中的 `upstream/ip_repo_v2022.2_original.zip`。
