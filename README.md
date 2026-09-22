# Eloquence TTS for Android

经典老头子语音 vvtts 的安卓移植版。基于 Apple tvOS 18 运行时提取的 Eloquence 引擎，支持 **14 种语言 + 8 个发音角色**，并内置多语言自动检测与零延迟实时切换。

> 前称 IBM ViaVoice TTS（vvtts）。被收购后改名 Eloquence，一度取消简体中文支持；苹果后来重新集成并加入中文。本项目把这套引擎完整移植到 Android，并补回了中文。

## 特性

- **14 种语言**：简体中文、繁体中文（台湾）、日文、韩文、英式/美式英语、德语、法语（法国/加拿大）、西班牙语（西班牙/墨西哥）、意大利语、葡萄牙语（巴西）、芬兰语。
- **8 个发音角色**：Reed / Sandy / Glen / Rocko / Bobby / Shelly / Grandpa / Grandma，通过 ECI voice param 机制切换。
- **多语言自动检测**：Unicode 区块 + Lingua 统计双层检测，混合语言文本自动分片，各用各的引擎朗读。
- **零延迟**：本地引擎，按下就出声，无云端往返。
- **自定义捏声**：长按发音角色可调节性别、头部大小、情感起伏、粗糙度、气息感等音色参数，创造专属声音。
- **语速 / 音调 / 音量**：可独立调节，试听实时生效。
- **系统 TTS 集成**：可作为 Android 无障碍 / 屏幕阅读器的语音引擎使用。

## 目录结构

```
├── src/                      Java 源码（engine / services / ui / utils）
├── jni/                      native 桥接层（ECI C API 封装）
├── native-libs/arm64-v8a/    编译好的语言库与引擎 .so
├── language-models/          Lingua 语言检测模型（JSON）
├── libs/                     第三方依赖 jar
├── res/                      Android 资源（多语言 strings）
├── AndroidManifest.xml
└── build.sh                  APK 构建脚本
```

## 构建

依赖环境：Linux + Android SDK（含 `aapt`、`d8`、`zipalign`、`apksigner`）+ JDK 11+。

```bash
# 下载依赖 jar（若 libs 目录为空）
# 见 libs/README 说明

# 构建 APK
bash build.sh
```

产物为 `vvtts_signed.apk`。

## 技术实现

### 引擎来源

语音数据提取自 **tvOS 18.2 Simulator Runtime** 中的 Eloquence dylib，通过 [apple-eloquence-elf](https://github.com/Mudb0y/Apple-Eloquence-ELF) 的 `macho2elf.py` 转换为 Android 可加载的 ELF `.so`。

参考的开源项目：

- [Mudb0y/Apple-Eloquence-ELF](https://github.com/Mudb0y/Apple-Eloquence-ELF) —— Apple Eloquence 语音 dylib 提取与 Mach-O → ELF 转换。
- [Mudb0y/openevv](https://github.com/Mudb0y/openevv) —— IBM ETI Eloquence 的便携 C 重写实现。
- [Mudb0y/trypsynth](https://github.com/Mudb0y/trypsynth) —— Android 端 Eloquence 集成参考。

### 角色切换

通过 ECI 的 `eciSetVoiceParam` 设置 8 维 voice 参数（gender、headSize、pitchBaseline、pitchFluctuation、roughness、breathiness、speed、volume）实现角色切换与自定义捏声。

### 多语言检测

双层架构：

1. **Unicode 规则层**：O(n) 单遍扫描，假名 → 日文，谚文 → 韩文，汉字 → 中文，拉丁 → 拉丁块。零延迟、确定性判断。
2. **Lingua 统计层**：拉丁 10 语言互分（en/de/fr/es/it/pt/fi），只处理拉丁块。

## License

本项目代码以 MIT License 开源。

语音数据与引用库的版权归各自所有者。Apple Eloquence 语音数据版权归 Apple Inc. 所有，仅供学习与研究使用。

## 免责声明

本仓库仅用于技术研究与学习交流，不构成任何商业用途的授权。使用 Apple 语音数据可能涉及版权问题，请遵守当地法律法规。