# 生成产物 / 唯一事实源契约

本仓库部分源文件为**生成产物**：禁止手工修改；改动必须改唯一事实源并重新生成。

修改数据时改错位置，会造成「下次生成时被覆盖 / CI 失败 / 数据静默丢失」。

## 唯一事实源（Source of Truth）

- `oracle/table/zh-cn.part1.consolidated.tsv`、`oracle/table/zh-cn.part2.consolidated.tsv`：中文语音数据主表（由采集/补采语料合并而来）。
- `oracle/corpus/zh-cn-sweep.tsv`：采集语料（生成命令不导入其编辑：仅供人工补采、校验参考）。
- 手写源码（`src/`、`oracle/*.py` 脚本、Shell 构建脚本，及 C 引擎源码（`oracle_chs.c` 除外——见下，属生成产物））：非生成产物，可直接修改并提交。


##生成产物

- `oracle_chs.c`：由 `oracle/merge_build.py` 确定性合并 consolidated TSV + legacy 行生成（16,913 字、16,913 PCM 行完整保留）；构建时经 `build_native.sh` 走生成器流水线。`chs_smoke.c` 亦属生成/校验配套。



##修改规则

1. 改数据 → 改唯一事实源（仅 consolidated TSV；`oracle/corpus/zh-cn-sweep.tsv` 为采集语料，`build_native.sh`/`merge_build.py` 不导入其编辑），不得改 `oracle_chs.c` 等生成产物。
2. 重新生成 → 在仓库根运行 `./build_native.sh`（或 `python3 oracle/merge_build.py oracle/table native/openevv/lang/chs/oracle_chs.c native/openevv/lang/chs/oracle_chs.c`），把新生成的产物与事实源改动一并提交；legacy 行由旧 `oracle_chs.c` 自动并入，增删改通过 consolidated TSV 事实源反映。
。
3. 确定性 → 同一事实源必须产出逐字节一致的生成结果；新增合并逻辑须保持确定性并过 smoke。
4. CI 校验 → `chs_smoke` 流水线在 CI 上校验生成结果可用；产物变更须带上过线结果。