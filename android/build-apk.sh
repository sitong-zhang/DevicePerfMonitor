#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Android 一键打包脚本：产出 signed release APK
#
# 用法：
#   ./build-apk.sh              # 有 keystore.properties → signed release APK
#                               # 无 keystore.properties → debug 签名 release APK（自动降级）
#   ./build-apk.sh --debug      # 强制 debug 包
#
# 环境要求：JDK 17+、Android SDK（ANDROID_HOME 或 local.properties）、网络（首次下载依赖）
# 输出：app/build/outputs/apk/release/app-release.apk
# ---------------------------------------------------------------------------
set -euo pipefail
cd "$(dirname "$0")"

FORCE_DEBUG=0
if [[ "${1:-}" == "--debug" ]]; then FORCE_DEBUG=1; fi

# ---- JDK 检查 ----
if ! command -v java >/dev/null 2>&1; then
  echo "[错误] 未找到 java，请先安装 JDK 17 或更高版本。" >&2
  exit 1
fi

# ---- Android SDK 检查（支持 ANDROID_HOME / ANDROID_SDK_ROOT / local.properties）----
if [[ -z "${ANDROID_HOME:-}" && -z "${ANDROID_SDK_ROOT:-}" && ! -f local.properties ]]; then
  echo "[错误] 未配置 Android SDK：请设置 ANDROID_HOME 环境变量，或在 android/ 目录创建 local.properties：" >&2
  echo "       sdk.dir=/path/to/Android/sdk" >&2
  exit 1
fi

chmod +x ./gradlew 2>/dev/null || true

if [[ "$FORCE_DEBUG" == "1" ]]; then
  echo "[build-apk] 构建 debug APK ..."
  ./gradlew :app:assembleDebug --no-daemon
  echo "[build-apk] 完成：app/build/outputs/apk/debug/app-debug.apk"
  exit 0
fi

if [[ -f keystore.properties ]]; then
  echo "[build-apk] 检测到 keystore.properties，构建 signed release APK ..."
  ./gradlew :app:assembleRelease --no-daemon
  APK="app/build/outputs/apk/release/app-release.apk"
  if [[ -f "$APK" ]]; then
    echo "[build-apk] 完成：$APK"
    echo "[build-apk] 签名校验："
    "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/build-tools"/*/apksigner verify --print-certs "$APK" 2>/dev/null \
      || keytool -printcert -jarfile "$APK" | head -5 || true
  else
    echo "[错误] 未找到输出 APK，请查看上方 Gradle 日志。" >&2
    exit 1
  fi
else
  echo "[build-apk] 未找到 keystore.properties，自动降级为 debug 签名的 release 包（不可上架，仅供测试）。"
  echo "[build-apk] 正式发布请按 keystore.properties.template 配置签名。"
  ./gradlew :app:assembleRelease --no-daemon
  echo "[build-apk] 完成：app/build/outputs/apk/release/app-release.apk（debug 签名）"
fi
