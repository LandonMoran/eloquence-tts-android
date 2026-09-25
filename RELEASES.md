# Release Notes — DRAFT（未发布）

> ⚠️ 草稿，仅供测试预览；未发布，未打 tag。

## Unreleased

### 🐛 修复

- **锁屏朗读修复**：`EloquenceEngine` 全部引擎文件（`eci.ini`、PCM 缓存）改写入设备保护存储（device-protected storage）。此前锁屏（未首次解锁前）引擎初始化写 CE 目录抛异常 → TTS 服务崩溃 → 锁屏（含密码屏）完全无声。`directBootAware` 现在真正生效，TalkBack 解锁前即可朗读。

- **中文语音库构建确定性化**：新增 `oracle/merge_build.py`，由 consolidated TSV + legacy 行确定性生成 `oracle_chs.c`，替代手工编辑 70MB C 文件；`build_native.sh` 改走生成器流水线。重修后 16,913 字、16913 PCM 行完整保留。



### ⚠️ 已知限制（下版本修复）

- **百 / 零 / 八 暂缺 PCM**：数字/数量朗读中这三个字静音（如 `一百二十三` 的 «百»、`零`、`八`）。原因：consolidated 表缺这三行。已排入采集流水线（`oracle-dump` + `oracle-assemble` 重采三字并合入表），合入后本限制消除。期间可用完整数字（`一二三…`）或拆分朗读。fro

### 🔧 测试要点

1. 锁屏：锁屏后（不解锁）触发 TalkBack/系统 TTS——应能朗读（含输密码场景）
2. 中文数字：`0 ~ 9`、`10`、`100`、`204` 等——除上述三字外应全部出声
3. 现有 14 语言回归：英语、日文、韩文、繁中、简中逐语试读一句