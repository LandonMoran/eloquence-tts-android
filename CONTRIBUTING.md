# CONTRIBUTING

## 构建（Building）

- 原生引擎：仓库根运行 `./build_native.sh`（走 oracle 生成器流水线，详见 `docs/artifacts-contract.md`）。
- APK：本地构建在仓库根运行 `./build.sh`（需 Linux + Android SDK + JDK 11+）；GitHub Actions CI（见 `.github/workflows/` 各 lane）亦以该脚本构建并校验，可读 CI 日志核验构建结果。

##测试（Testing）

- `chs_smoke` lane：中文语音数据回归。
- `emu-smoke` lane：Android 模拟器冒烟测试。
- 发布前手工回归清单见 `RELEASES.md`「测试要点」。


##架构（Architecture）

- `src/com/xw/vvtts/`：Kotlin 源码（TTS 服务、设置、检测器等）。
- `oracle/`：中文语音数据采集/合并/生成流水线（`merge_build.py` 确定性生成 `oracle_chs.c`）。
- 语言检测三层架构：Unicode 规则 → 内存 n-gram 表 →→ Lingua 统计（详见 `RELEASES.md` 新特性）。
- 版本策略与发布通道见 `RELEASES.md`；功能总览见 `README.md`。


##提交与 PR（PR Conventions）

- 分支命名：`fix/issue-<编号>`；PR 标题沿用 issue 标题，body 标注 `Fixes #<编号>`。
- 每个 PR 应附 CodeRabbit 评审（在 PR 上评论 `@coderabbitai autofix review`）。
- 目标分支：`main`；CI 须绿；未经 owner 明确批准不自行合并。


##发布（Releases）

- 版本/发布契约、资产命名契约见 `RELEASES.md`；每次发布须更新该文件并打语义化 tag。