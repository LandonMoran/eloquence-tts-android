# 语言 / 角色 / 能力 —— 唯一事实源

本文档是仓库中「支持的语言」「发音角色」「能力清单」的唯一事实源；README 特性列表、设置 UI 文案、发布说明等凡涉及上述清单之处，均以此表为准，如需增删请改本文件并同步发布说明。

##支持的语言（14）

| Locale | 语言| 说明 |
|---|---|---|
| `zh-CN` | 简体中文 | 主开发语言，自带 oracle 语音库 |
| `zh-TW` | 繁体中文（台湾） | |
| `ja` | 日文 | |
| `ko` | 韩文 | |
| `en-GB` | 英式英语 | |
| `en-US` | 美式英语 | |
| `de` | 德语 | |
| `fr-FR` | 法语（法国） | |
| `fr-CA` | 法语（加拿大） | |
| `es-ES` | 西班牙语（西班牙） | |
| `es-MX` | 西班牙语（墨西哥） | |
| `it` | 意大利语 | |
| `pt-BR` | 葡萄牙语（巴西） | |
| `fi` | 芬兰语 | |

##发音角色（8）

| 角色 | 说明 |
|---|---|
| Reed | |
| Sandy | |
| Glen | |
| Rocko | |
| Bobby | |
| Shelly | |
| Grandpa | |
| Grandma | |

> 角色可用性因语言而异（以引擎实际数据为准）；UI 中未出现在当前语言下的角色即不被支持，不在此列外列。



##能力清单

- 多语言自动检测：Unicode 规则（O(1(）→ 内存 n-gram → Lingua 三层，混合语言文本自动分片。
- 零延迟：本地引擎，无云端往返。

- 系统 TTS 集成：可作为 Android 无障碍 / 屏幕阅读器语音引擎使用；`directBootAware` + 设备保护存储支持锁屏朗读。

- 音色参数（8 维）：gender / headSize / pitchBaseline / pitchFluctuation / roughness / breathiness / speed / volume（长按发音角色调节）。
- 语速 /  音调 / /音量独立可调，试听实时生效。

- 自动更新：GitHub Releases 通道，按 ABI 匹配 APK，语义化版本对比（详见 `RELEASES.md`）。