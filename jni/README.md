# Native 桥接层

本目录包含 native 桥接层源码。当前方案（苹果 Eloquence 完整引擎）实际使用：

- **vvtts_core.c** —— 自研 ECI 桥接层（当前生产方案），编译产物 `libvvtts_core.so`
- **role_table.h** —— KonaVoice 8 角色参数表头文件

以下为早期探索保留（广荣内核方案，已停用）：

- **vvtts_bridge.c** —— 广荣内核桥接层（历史）
- **vvtts_voice.c** —— 广荣 voice 参数调度（历史）

## 引擎架构说明

语音引擎本体是 Apple tvOS 18.2 的 `eci.dylib` 经 `macho2elf` 转换的 `libeci.so`，加上 14 个语言库（`libchs.so` / `libcht.so` / `libjpn.so` / `libkor.so` / `libeng.so` 等）。

`vvtts_core.c` 是这一套引擎的 JNI 封装，核心配方：

```
eciNewEx(dialect) → eciRegisterKlattHooks2(h, 0, 0, 0) → eciRegisterCallback
→ eciSetOutputBuffer → eciAddText → eciSynthesize → eciSynchronize
```

关键点：

- `eciAddText` 是标准 2 参（ECIHand, text），非广荣的 4 参
- 必须注册空 Klatt 钩子，否则 CJK synthesize 内部 `blr` 空指针触发 SIGSEGV
- 采样率 `SetParam(5,1)` = 11025Hz（苹果只支持 8k / 11.025k，不支持 22050）

## 编译 libvvtts_core.so

```bash
NDK=/usr/lib/android-sdk/ndk/26.3.11579264
/usr/lib/llvm-18/bin/clang -shared -fPIC -O2 \
  --target=aarch64-linux-android28 \
  --sysroot=$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot \
  --rtlib=compiler-rt \
  -resource-dir $NDK/toolchains/llvm/prebuilt/linux-x86_64/lib/clang/17 \
  -I$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include \
  -o libvvtts_core.so vvtts_core.c -landroid -llog
```

> 注意：NDK 自带的 `clang` 在部分环境有执行权限问题（`---x--x--x`），可用系统 `llvm-18` 交叉编译替代，通过 `--rtlib=compiler-rt -resource-dir` 解决 `-lgcc` 缺失。

编译产物 `libvvtts_core.so` 放入 `native-libs/arm64-v8a/` 即可（语言库 `.so` 已预编译存放于该目录）。