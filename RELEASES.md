# Release Notes — v1.0

> 首个正式发布。版本策略：1.0 → versionCode 2000000001（沿用 r37 占位版（versionCode 2000000000）之后的编号，首个正式版起每次发布 versionCode +1），tag 采用语义化版本（`v1.0`、`v1.1` …）。APK 从 GitHub Releases 直接分发（非 Play 商店）。

## 📦 发布 / 版本契约

- **Tag 语义**：语义化版本（`v1.0`、`v1.1` …），每次发布打对应 tag 并在本文件补发布说明。
- **versionCode 单调递增**：r37 占位版 `2000000000` 之后，首个正式版 `2000000001`，此后每次发布 `+1`，严禁回退（设备以 versionCode 判定降级,更低版本号不被接受为升级）。
- **分发通道**：GitHub Releases 直接分发 APK，非 Play 商店；自动更新按设备 ABI 匹配资产,某 ABI 资产缺失时回退为打开 Releases 页。
- **资产命名契约**：`pickAsset` 精确匹配文件名（`vvtts-arm64-v8a.apk` / `vvtts-armeabi-v7a.apk` / `vvtts-universal.apk`）,发布物文件名不得改动（详见"已知限制"）。

## ✨ 新特性

- **n-gram 统计分析器（核心）**：拉丁语系检测改为「Unicode 规则 → 内存 n-gram 表 → Lingua 统计」三层架构。n-gram 表常驻内存、零分配 Int-key 打分、加载时按语言做词频归一化，短句与混合文本的拉丁语识别更快更准（德语/法语/西语等 10 种拉丁语言互分改善）。
- **自动更新（GitHub Releases 通道）**：设置页新增「检查更新」。启动时检测官方 Releases 的最新版本（语义化版本对比 v1.0 起生效），按设备 ABI 匹配 `vvtts-arm64-v8a.apk` / `vvtts-armeabi-v7a.apk` / `vvtts-universal.apk`，可下载并安装新版本；无匹配资产时回退为打开 Releases 页面。
- **锁屏朗读（注销前）**：`EloquenceEngine` 全部引擎文件（`eci.ini`、PCM 缓存）改用设备保护存储（device-protected storage）。此前锁屏（未首次解锁前）引擎初始化写 CE 目录抛异常 → TTS 服务崩溃 → 锁屏（含密码屏）完全无声。`directBootAware` 现在真正生效，TalkBack 解锁前即可朗读。
- **中文语音库构建确定性化**：新增 `oracle/merge_build.py`，由 consolidated TSV + legacy 行确定性生成 `oracle_chs.c`，替代手工编辑 70MB C 文件；`build_native.sh` 改走生成器流水线。重修后 16,913 字、16913 PCM 行完整保留。
- **葡萄牙语默认角色修正**：pt 语言默认发音角色映射到已内置的 pt-BR（此前默认指向未上船的角色，表现异常）。

## 🛠 稳定性与修复

- **停止竞态 / 幽灵朗读**：`onStop` 即作废排队中的 utterance；synth 循环在过代（stale generation）时提前退出；旋转音色前先尽力 stop 旧句柄；stop-flag 复位竞态关闭。连续快速停止/切音不再残留"幽灵朗读"或卡死。
- **回调终止保证**：`start()`/`done()` 抛错、executor 拒收任务时仍保证回调 `error()` 送达（start-before-done 契约）；修复零缓冲无限循环防护。
- **崩溃/ANR 扫排**（多模型评审驱动）：UserManager 安全访问（多用户切换不崩）、engine executor 随服务销毁、stop 竞态、zh 发音角色锁定不再强制重建 Lingua（省内存）、`accentHint` 缺失时 continue 而非中断。
- **系统 TTS 兼容**：`onGetLanguage` 返回合法 3 元素 locale；数字串不再向未上船方言播种检测结果。
- **检测精度**：移除短片段 1.0 分数地板偏差（短句更准）；数字串守卫；n-gram 表加载失败/异常时安全回退 Lingua。
- **设置/引擎硬化**：`volatile` 核心写；旋转时清理过期语音缓存；应用发音角色前先刷新设置。
- **工程/CI**：修复 CI 双触发（`x86_64` smoke lane）；v1.0 起引入 emulator CI smoke 流水线（chs-smoke（，构建脚本走生成器流水线。

## ⚠️ 已知限制

- **百 / 零 / 八 暂缺 PCM**：数字/数量朗读中这三个字静音（如 `一百二十三` 的「百」、「零」、「八」）。原因：consolidated 表缺这三行。已排入采集流水线（`oracle-dump` + `oracle-assemble` 重采三字并合入表），合入后本限制消除。期间可用完整数字（`一二三…`）或拆分朗读。
- **自动更新依赖 GitHub Releases 资产命名**：发布时必须携带与 `pickAsset` 完全一致的 APK 文件名（见上）；名称不符时仅回退到浏览器打开 Release 页。
- **首版分发形态**：直接分发 APK（arm64-v8a / armeabi-v7a / universal），未接入 Play 商店；Android 可能提示"未知来源"安装需手动放行。

## 🔧 测试要点（发布前回归清单）

1. 锁屏：锁屏后（不解锁）触发 TalkBack/系统 TTS——应能朗读（含输密码场景）
2. 中文数字：`0 ~ 9`、`10`、`100`、`204` 等——除上述三字外应全部出声
3. 现有 14 语言回归：英语、日文、韩文、繁中、简中逐语试读一句；拉丁 10 语互分抽查短句（如 "Guten Tag"、"Hola"）
4. 自动更新：安装旧版本 APK → 新版本发布后应提示更新；最新版不提示
5. 停止/切音压力：连续快速停止、切音、切语言——无幽灵朗读、无 ANR、无 SIGSEGV
6. 快速安装升级路径：`pm install -r` 升级安装（低版本 → 高版本）不丢设置