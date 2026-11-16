#!/usr/bin/env bash
set -e
echo "========================================================"
echo "  自动点击器高级版 (com.xundao.clicker) 一键编译脚本"
echo "========================================================"

if command -v gradle >/dev/null 2>&1; then
    echo "[1/2] 检测到系统 Gradle，开始编译 Debug APK..."
    gradle assembleDebug
elif [ -f "./gradlew" ]; then
    chmod +x ./gradlew
    ./gradlew assembleDebug
else
    echo "[提示] 未检测到 gradle 命令，请使用 Android Studio 打开本目录或上传至 GitHub Actions 自动编译。"
    exit 1
fi

if [ -f "app/build/outputs/apk/debug/app-debug.apk" ]; then
    cp "app/build/outputs/apk/debug/app-debug.apk" "./com.xundao.clicker_debug.apk"
    echo "[2/2] 编译成功！APK 路径：$(pwd)/com.xundao.clicker_debug.apk"
fi
