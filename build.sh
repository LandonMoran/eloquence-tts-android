#!/bin/bash
# Eloquence TTS for Android 构建脚本
# 依赖：Linux + Android SDK（aapt/d8/zipalign/apksigner）+ JDK 11+
#
# 使用前请确认环境变量：
#   ANDROID_SDK   Android SDK 根目录（默认 /usr/lib/android-sdk）
#   BUILD_TOOLS   build-tools 版本（默认 35.0.0）
#   KEYSTORE      签名 keystore 路径（默认 vvtts.jks）
#   KS_PASS       keystore 密码（默认 android123）
#   KEY_PASS      key 密码（默认 android123）

set -u
SDK="${ANDROID_SDK:-/usr/lib/android-sdk}"
BUILD_TOOLS="${BUILD_TOOLS:-35.0.0}"
AAPT="$SDK/build-tools/$BUILD_TOOLS/aapt"
D8="$SDK/cmdline-tools/latest/bin/d8"
ZIPALIGN="$SDK/build-tools/$BUILD_TOOLS/zipalign"
APKSIGNER="$SDK/build-tools/$BUILD_TOOLS/apksigner"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
KEYSTORE="${KEYSTORE:-vvtts.jks}"
KS_PASS="${KS_PASS:-android123}"
KEY_PASS="${KEY_PASS:-android123}"

# 工作目录 = 脚本所在目录
cd "$(dirname "$0")"

LIBS_DIR="libs"

# 0. 用 aapt 编译资源并生成 R.java
rm -rf gen
mkdir -p gen
"$AAPT" package -f -m -J gen -M AndroidManifest.xml -S res -I "$ANDROID_JAR" 2>&1
if [ $? -ne 0 ]; then echo "AAPT GEN R FAILED"; exit 1; fi

# 1. 编译所有 Kotlin 源码（Phase 1: 全 Kotlin，零 Java）
KOTLINC="${KOTLINC:-/opt/kotlinc/kotlinc/bin/kotlinc}"
# 备选：KOTLINC_CP 指向 "compiler:trove4j:stdlib" 三个 jar，用 Java 直启 K2JVMCompiler（免解压）
KOTLINC_CP="${KOTLINC_CP:-}"
rm -rf out_classes
mkdir -p out_classes
find src gen -name '*.kt' > /tmp/kt_sources.txt
# aapt 生成的 R.java 是 Java 源，先单独用 javac 编进 out_classes
find gen -name '*.java' > /tmp/r_sources.txt
if [ -s /tmp/r_sources.txt ]; then
  javac -source 11 -target 11 -classpath "$ANDROID_JAR" -d out_classes @/tmp/r_sources.txt 2>&1
  if [ $? -ne 0 ]; then echo "R COMPILE FAILED"; exit 1; fi
fi
KT_CLASSPATH="$ANDROID_JAR:out_classes:$LIBS_DIR/lingua-slim.jar:$LIBS_DIR/kotlin-stdlib-1.9.25.jar"
if [ -n "$KOTLINC_CP" ]; then
  java -cp "$KOTLINC_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -jvm-target 1.8 -classpath "$KT_CLASSPATH" -d out_classes @/tmp/kt_sources.txt 2>&1
else
  "$KOTLINC" -jvm-target 1.8 -classpath "$KT_CLASSPATH" -d out_classes @/tmp/kt_sources.txt 2>&1
fi
if [ $? -ne 0 ]; then echo "COMPILE FAILED"; exit 1; fi

# 2. D8 打包 dex
rm -rf out_dex
mkdir -p out_dex
find out_classes -name '*.class' > /tmp/class_files.txt
"$D8" --min-api 28 --output out_dex @/tmp/class_files.txt \
  "$LIBS_DIR/lingua-slim.jar" \
  "$LIBS_DIR/kotlin-stdlib-1.9.25.jar" \
  "$LIBS_DIR/fastutil.jar" \
  "$LIBS_DIR/moshi.jar" \
  "$LIBS_DIR/moshi-kotlin.jar" \
  "$LIBS_DIR/okio.jar" \
  "$LIBS_DIR/kotlin-reflect.jar" 2>&1
if [ $? -ne 0 ]; then echo "D8 FAILED"; exit 1; fi

# 2.5. Build the native bridge first if missing (openevv + JNI core,; no Apple code(.
# CI runs build_native.sh as its own earlier step; this guard covers fresh clones
# where only build.sh was invoked.  ABI defaults to arm64-v8a (phones); the
# emulator-test workflow passes ABI=x86_64.
ABI="${ABI:-arm64-v8a}"
if [ ! -f "native-libs/$ABI/libvvtts_core.so" ]; then
  echo "libvvtts_core.so missing -- running build_native.sh first..."
  ABI="$ABI" bash build_native.sh || exit $?
fi

# 3. 组装 APK
rm -rf tmp_apk vvtts_base.apk vvtts_unsigned.apk vvtts_aligned.apk vvtts_signed.apk
mkdir -p "tmp_apk/lib/$ABI" tmp_apk/assets

# 复制所有 dex（multidex）
cp out_dex/classes*.dex tmp_apk/
# 复制 native 语言库
cp "native-libs/$ABI"/*.so "tmp_apk/lib/$ABI/"
# 复制 Lingua 语言模型 JSON（必须放进 APK 根目录）
cp -r language-models tmp_apk/

"$AAPT" package -f -M AndroidManifest.xml -S res -A tmp_apk/assets -I "$ANDROID_JAR" -F vvtts_base.apk 2>&1
if [ $? -ne 0 ]; then echo "AAPT FAILED"; exit 1; fi

cp vvtts_base.apk vvtts_unsigned.apk
cd tmp_apk && zip -r ../vvtts_unsigned.apk classes*.dex language-models lib > /dev/null && cd ..

# 4. 签名
"$ZIPALIGN" -f 4 vvtts_unsigned.apk vvtts_aligned.apk
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" --out vvtts_signed.apk vvtts_aligned.apk
"$APKSIGNER" verify vvtts_signed.apk 2>&1 | head -3

echo "=== BUILD DONE ==="
ls -lh vvtts_signed.apk