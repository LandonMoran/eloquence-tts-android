# 第三方依赖 jar

本项目构建依赖以下 jar，均已放在本目录。若需要重新下载，来源如下：

| 文件 | 作用 | Maven 坐标 / 下载地址 |
|------|------|----------------------|
| lingua-slim.jar | Lingua 语言检测（只含 10 语言模型的瘦身版） | `com.github.pemistahl:lingua:1.2.2` |
| kotlin-stdlib-1.9.25.jar | Kotlin 标准库 | `org.jetbrains.kotlin:kotlin-stdlib:1.9.25` |
| kotlin-reflect.jar | Kotlin 反射（moshi-kotlin 依赖） | `org.jetbrains.kotlin:kotlin-reflect:1.9.25` |
| fastutil.jar | 高性能集合库（Lingua 依赖） | `it.unimi.dsi:fastutil:8.5.12` |
| moshi.jar | JSON 解析（Lingua 依赖） | `com.squareup.moshi:moshi:1.15.0` |
| moshi-kotlin.jar | Moshi Kotlin 扩展 | `com.squareup.moshi:moshi-kotlin:1.15.0` |
| okio.jar | IO 库（OkHttp 依赖，Lingua 模型加载需要） | `com.squareup.okio:okio:2.10.0` |

## lingua-slim.jar 瘦身说明

官方 `lingua-1.2.2.jar` 约 80MB，包含全部 75 语言的模型。本项目只需要 10 语言（en/de/fr/es/it/pt/fi/zh/ja/ko），因此做了瘦身：

1. 下载官方 jar
2. 解压，只保留 `com/github/pemistahl/**` 的 class + `language-models/{en,de,fr,es,it,pt,fi,zh,ja,ko}/**` 的 JSON
3. 重新打包

同时，Lingua 依赖的第三方库（kotlin-stdlib、fastutil、moshi、moshi-kotlin、okio、kotlin-reflect）需要在 build 时一起通过 d8 打进 dex。详见 `build.sh`。

> 注意：`language-models/` 目录的 JSON 必须打进 APK 根目录（`Class.getResourceAsStream` 从 classpath 根目录读取），这是打包时的关键一步，漏了会导致欧洲语言检测静默失败。