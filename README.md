# Eloquence TTS for Android

经典老头子语音 vvtts 的安卓移植版，基于 Apple tvOS 18 运行时提取的 Eloquence 引擎，支持 **14 种语言 + 8 个发音角色**，内置多语言自动检测与零延迟实时切换。



## 特性

- **14 种语言**：简体中文、繁体中文（台湾）、日文、韩文、英式/美式英语、德语、法语（法国/加拿大）、西班牙语（西班牙/墨西哥）、意大利语、葡萄牙语（巴西）、芬兰语
- **8 个发音角色**：Reed / Sandy / Glen / Rocko / Bobby / Shelly / Grandpa / Grandma，通过 ECI voice param 机制切换
- **多语言自动检测**：Unicode 区块 + Lingua 统计双层检测，混合语言文本自动分片，各用各的引擎朗读
- **零延迟**：本地引擎，按下就出声，无云端往返
- **自定义捏声**：长按发音角色可调节性别、头部大小、情感起伏、粗糙度、气息感等音色参数
- **语速 / 音调 / 音量**：可独立调节，试听实时生效
- **系统 TTS 集成**：可作为 Android 无障碍 / 屏幕阅读器的语音引擎使用
- **锁屏朗读**：`directBootAware` + 设备保护存储，解锁前（含锁屏输密码）TalkBack 也能用本引擎朗读

## 目录结构

```
├── src/                      Java/Kotlin 源码（engine / services / ui / utils）
├── jni/                      native 桥接层（ECI C API 封装）
├── oracle/                  中文语音库（采集 / 合并 / 生成流水线）
│   ├── table/*.consolidated.tsv   一行一汉字，16-bit PCM（11.025kHz）
│   ├── corpus/                   扫字表与采样文本
│   ├── merge_build.py            确定性生成 native/.../oracle_chs.c
│   └── README.md                 逆向采集笔记
├── native/openevv/             引擎与语音库 C 源码
├── native-libs/arm64-v8a/    编译好的语言库与引擎 .so
├── language-models/             Lingua 语言检测模型
├── libs/                       第三方依赖 jar
├── res/                        Android 资源（多语言 strings）
├── AndroidManifest.xml
├── build.sh                    APK 构建脚本
└── build_native.sh             oracle C 语音库交叉编译
```

## 构建

依赖环境：Linux + Android SDK（含 `aapt`、`d8`、`zipalign`、`apksigner`）+ JDK 11+。

```bash
# 重建 zh-CN oracle 语音库（可选，改过 oracle/table 后执行）
python3 oracle/merge_build.py
./build_native.sh

# 构建 APK
bash build.sh
```

产物为 `vvtts_signed.apk`。CI（`.github/workflows/build-apk.yml`）在 `fix/**` push 时自动构建。



## 技术实现

### 引擎来源

语音数据提取自 **tvOS 18.2 Simulator Runtime** 中的 Eloquence dylib，通过 [apple-eloquence-elf](https://github.com/Mudb0y/Apple-Eloquence-ELF) 的 `macho2elf.py` 转换为 Android 可加载的 ELF `.so`**

参考的开源项目：
- [Mudb0y/Apple-Eloquence-ELF](https://github.com/Mudb0y/Apple-Eloquence-ELF)
- [Mudb0y/openevv](https://github.com/Mudb0y/openevv)
- [Mudb0y/trypsynth](https://github.com/Mudb0y/trypsynth)

### 中文 oracle 语音库

- `oracle/table/*.consolidated.tsv`：一行一个汉字 + 真人 PCM，采集自参考 Eloquence 引擎
- `oracle/merge_build.py`：把 consolidated TSV + legacy 行确定性合并成 `oracle_chs.c`（16,913 字）
- `oracle-dump` workflow（arm64 CI）可重采缺失字（如 百 / 零 / 八。，产物 `corpus/zh-cn-hanzi.tsv`，再由 `oracle-assemble` 合并回表
- 数字 0-9 由 `TextNormalizer` 归一为汉字（零一二…（，经同一语音库朗读

###角色切换

通过 ECI 的 `eciSetVoiceParam` 设置 8 维 voice 参数（gender、headSize、pitchBaseline、pitchFluctuation、roughness、breathiness、speed、volume）实现。

###多语言检测

双层架构：Unicode 规则层（O(n) 判定假名/谚文/汉字/拉丁（+ Lingua 统计层（拉丁 10 语言互分。



## License

本项目代码以 MIT License 开源。语音数据与引用库版权归各自所有者；Apple Eloquence 语音数据版权归 Apple Inc.，仅供学习与研究使用。

## 免责声明

本仓库仅用于技术研究与学习交流，不构成任何商业用途的授权。使用 Apple 语音数据可能涉及版权问题，请遵守当地法律法规。

## Roadmap

- [ ] 重采 百 / 零 / 八 PCM（`oracle-dump` + `oracle-assemble`（，合上最后一个数字静音口
- [ ] 发布说明见 `RELEASES.md`（草稿，未发布。）