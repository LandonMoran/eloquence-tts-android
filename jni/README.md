# Native 桥接层（openevv 方案）

本目录包含 native 桥接层源码，以及引擎本体随 `native/openevv/` 一并 vendor（MIT 协议，
IBM Eloquence/ETI 的可移植 C 重实现，**不含任何 Apple 代码**）。

- **`vvtts_core.c`** —— JNI 桥接层（唯一 native 源码），把 Kotlin 端的 8 个
  `@JvmStatic external fun` 接到 openevv 的 `eci.h` API。
  - 对应 `com.xw.vvtts.core.VvttsCore`（`System.loadLibrary("vvtts_core")`）：
    `nativeInitEngine` `nativeSynthesize` `nativeSetVoiceParam` `nativeGetVoiceParam`
    `nativeSetParam` `nativeSetStandardVoice` `nativeStop` `nativeShutdown`
  - 采样率 `eciSampleRate=1`（11,025 Hz，引擎原生格式，与 Kotlin 播放端一致）
  - voice 参数沿用原 ECI 编号（gender/head/pitch/fluctuation/roughness/breath/speed/volume），
    并按 Kotlin 预设表做 pitch 40–120 → openevv 0–100 的钳位。
激活 voice 恒为 0：
    `eciCopyVoice(from, 0)` 把 8 个预设（Reed…Eddy、拷到活动 voice 上用 Kotlin 微调。
  - 语音合成是异步回调（`eciRegisterCallback` 收集 `eciWaveformBuffer`），会话用
    `eciSpeaking` 轮询等收尾——和旧桥层同一套契约。Kotlin 端已有合成锁，每次会话单线程驱动即可。

## 构建

`build_native.sh`（仓库根目录）把引擎+桥层**静态链接成单一 `native-libs/arm64-v8a/libvvtts_core.so`，
并清空该目录下全部旧 `.so`（旧 Apple 语言库 `.so` 已从仓库删除，不再随 APK 分发）。

- 交叉编译：`make CC=<NDK aarch64 clang> CFLAGS=-fPIC RULES=c` —— C 规则内联
  （冷启动/延迟低于 bytecode 规则），10 个 IBM 语言全量打包进同一镜像。
- 引擎运行时不读任何文件、不依赖任何库（仅 libm）。`.so` 自包含，APK 只需这一个 native 文件。
- 唯一构件产物路径写死为 `native-libs/arm64-v8a/libvvtts_core.so`（已 gitignore）；CI 每次重新生成。

>

## 测试路径

CI（GitHub Actions `build.yml`）是唯一官方构建机器：NDK 出自 runner 镜像，产物直接进
`build.sh` 的 APK 组装（`cp native-libs/arm64-v8a/*.so tmp_apk/lib/arm64-v8a/`）。本地只允许
做源码级语法检查；开发机上不得跑 Android SDK/NDK 构建。


## 语言状态

引擎打包 10 个 IBM 语言：en-US、en-GB、de-DE、fr-FR、fr-CA、es-ES、es-US、it-IT、
ja-JP、pl-PL。Kotlin UI 中的 zh/pt/ko/fi 槽位暂由引擎默认回退；添加新语言（含 zh-CN）见
`native/openevv/docs/language.md`（数据编写工程，非代码工程）；质量改进（采样率 22.05k/44.1k、
韵律规则微调）同样在文档里。