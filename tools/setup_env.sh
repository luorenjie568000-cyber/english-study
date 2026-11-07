#!/bin/sh
# 重建构建环境（沙箱重启后重跑本脚本即可）
# 用法: sh setup_env.sh
set -e

mkdir -p /tmp/es_env/lib /tmp/es_env/bt /tmp/es_assets

echo "== 1. android-all.jar (用于 javac 编译 + aapt2 资源链接)"
if [ ! -s /tmp/es_env/lib/android-all.jar ]; then
  curl -sL --max-time 900 -o /tmp/es_env/lib/android-all.jar \
    "https://maven.aliyun.com/repository/central/org/robolectric/android-all/14-robolectric-10818077/android-all-14-robolectric-10818077.jar"
fi
ls -lh /tmp/es_env/lib/android-all.jar

echo "== 2. android.jar (d8 需要纯 framework 版)"
if [ ! -s /tmp/es_env/lib/android.jar ]; then
  # 从 android-all 里没有 java.lang，d8 的 --lib 用 android-all 亦可
  cp /tmp/es_env/lib/android-all.jar /tmp/es_env/lib/android.jar
fi

echo "== 3. r8/d8"
if [ ! -s /tmp/es_env/lib/r8.jar ]; then
  curl -sL --max-time 600 -o /tmp/es_env/lib/r8.jar \
    "https://maven.aliyun.com/repository/google/com/android/tools/r8/8.2.42/r8-8.2.42.jar"
fi
ls -lh /tmp/es_env/lib/r8.jar

echo "== 4. apksig"
if [ ! -s /tmp/es_env/lib/apksig.jar ]; then
  curl -sL --max-time 300 -o /tmp/es_env/lib/apksig.jar \
    "https://maven.aliyun.com/repository/google/com/android/tools/build/apksig/8.2.2/apksig-8.2.2.jar"
fi
ls -lh /tmp/es_env/lib/apksig.jar

echo "== 5. aapt2 + zipalign (静态 aarch64 build-tools)"
if [ ! -x /tmp/es_env/bt/aapt2 ]; then
  curl -sL --max-time 600 -o /tmp/es_env/bt.zip \
    "https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip"
  cd /tmp/es_env && unzip -oq bt.zip && cp build-tools/* bt/ 2>/dev/null || true
  chmod +x bt/*
fi
ls /tmp/es_env/bt/

echo "== 6. sherpa-onnx 运行时（离线语音）"
if [ ! -s /tmp/es_env/sherpa.aar ]; then
  curl -sL --max-time 900 -o /tmp/es_env/sherpa.aar \
    "https://hf-mirror.com/csukuangfj/sherpa-onnx-libs/resolve/main/android/aar/sherpa-onnx-static-link-onnxruntime-1.10.45.aar"
fi
ls -lh /tmp/es_env/sherpa.aar

echo "== 7. kotlin-stdlib (sherpa 类依赖)"
if [ ! -s /tmp/es_env/lib/kotlin-stdlib.jar ]; then
  curl -sL --max-time 300 -o /tmp/es_env/lib/kotlin-stdlib.jar \
    "https://maven.aliyun.com/repository/public/org/jetbrains/kotlin/kotlin-stdlib/1.9.24/kotlin-stdlib-1.9.24.jar"
fi

echo "== 完成"
