#!/bin/sh
# 无 Gradle 的 aarch64 APK 构建流程
# 用法: sh tools/build.sh [debug|release]
#
# 关键教训（不要再踩）：
#   1. sherpa 的 Java 类 + kotlin-stdlib 必须作为 D8 的【输入文件】，只放 --classpath 不会打进 dex
#   2. MainActivity 在 com.englishstudy.app 包，引用子包类必须显式 import
#   3. javac 失败时 set -e 不会中断管道，必须显式检查产物是否存在
set -e

MODE="${1:-debug}"
ROOT=/var/minis/workspace/es
SRC=$ROOT/app/src/main
OUT=/tmp/es_out
ENV=/tmp/es_env
BT=$ENV/bt
ANDROID_ALL=$ENV/lib/android-all.jar
KOTLIN=$ENV/lib/kotlin-stdlib.jar
SHERPA=$ENV/sherpa/classes.jar
LLAMA=$ROOT/libs/llama.jar
R8=$ENV/lib/r8.jar

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/asm"

echo "== 1. aapt2 compile"
"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/res.zip"

echo "== 2. aapt2 link"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$ANDROID_ALL" \
  --manifest "$SRC/AndroidManifest.xml" \
  --java "$OUT/gen" --min-sdk-version 24 --target-sdk-version 34 \
  --version-code 540 --version-name "5.4" \
  "$OUT/res.zip"

echo "== 3. javac"
find "$SRC/java" "$OUT/gen" -name '*.java' > "$OUT/srcs.txt"
javac -nowarn -encoding UTF-8 -source 8 -target 8 \
  -cp "$ANDROID_ALL:$KOTLIN:$SHERPA:$LLAMA" \
  -d "$OUT/classes" @"$OUT/srcs.txt" 2>&1 \
  | grep -v -E 'bootstrap class path|source value 8|target value 8|deprecat|警告|warning|^Note' | head -50 || true

if [ ! -f "$OUT/classes/com/englishstudy/app/MainActivity.class" ]; then
  echo "!! javac 失败：MainActivity.class 未生成"
  exit 1
fi
echo "   class 文件数: $(find "$OUT/classes" -name '*.class' | wc -l)"

echo "== 4. d8"
find "$OUT/classes" -name '*.class' > "$OUT/cls.txt"
# sherpa 与 kotlin 必须作为输入打包进 dex
echo "$SHERPA" >> "$OUT/cls.txt"
echo "$LLAMA" >> "$OUT/cls.txt"
echo "$KOTLIN" >> "$OUT/cls.txt"
java -cp "$R8" com.android.tools.r8.D8 \
  --release --min-api 24 \
  --lib "$ANDROID_ALL" \
  --output "$OUT/dex" @"$OUT/cls.txt" 2>&1 | grep -v -E 'Warning|warning' | head -20 || true

if [ ! -f "$OUT/dex/classes.dex" ]; then
  echo "!! d8 失败"
  exit 1
fi
echo "   dex: $(ls -lh "$OUT/dex/classes.dex" | awk '{print $5}')"

echo "== 5. 组装"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
mkdir -p "$OUT/asm/lib/arm64-v8a"
cp "$ENV/sherpa/jni/arm64-v8a/libsherpa-onnx-jni.so" "$OUT/asm/lib/arm64-v8a/"
if [ -d "$ROOT/app/src/main/jniLibs/arm64-v8a" ]; then
  cp "$ROOT/app/src/main/jniLibs/arm64-v8a/"*.so "$OUT/asm/lib/arm64-v8a/"
fi
cp "$OUT/dex/classes.dex" "$OUT/asm/classes.dex"
if [ -d "$SRC/assets" ]; then
  cp -r "$SRC/assets" "$OUT/asm/assets"
fi
( cd "$OUT/asm" && zip -qr "$OUT/unsigned.apk" . )

echo "== 6. zipalign"
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "== 7. 签名"
java -cp "$ENV/lib/apksig.jar:$ROOT/tools" SignApk \
  "$ROOT/tools/es.keystore" es123456 es \
  "$OUT/aligned.apk" "$OUT/es-$MODE.apk"

ls -lh "$OUT/es-$MODE.apk"
echo "OK -> $OUT/es-$MODE.apk"
