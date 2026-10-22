#!/usr/bin/env bash
# AMFlymeLyric build script: javac -> d8 -> aapt2 -> jar -> apksigner
#
# 依赖：Android SDK（build-tools + platforms）、JDK 11+。
# 可选环境变量：
#   ANDROID_SDK / ANDROID_HOME      Android SDK 根目录（必需）
#   BUILD_TOOLS_VERSION             默认取 SDK 里最高版本
#   COMPILE_SDK_VERSION             默认取 SDK 里最高 platform
#   JAVA_HOME / JAVAC / JAVA / JAR / KEYTOOL   JDK 位置（默认用 PATH）
#   XPOSED_API_JAR                  自带 libxposed API jar 时指定，跳过 stub/ 编译
#   KEYSTORE / KEYSTORE_PASS / KEY_ALIAS     签名信息（默认 build/debug.keystore + android/amflyme）
#   MIN_SDK / TARGET_SDK            默认 30 / 35
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_SDK:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}}"
if [ -z "$SDK" ] || [ ! -d "$SDK" ]; then
  echo "错误：没有找到 Android SDK，请先 export ANDROID_SDK=/path/to/Android/Sdk" >&2
  exit 1
fi

BT_VERSION="${BUILD_TOOLS_VERSION:-$(ls "$SDK/build-tools" 2>/dev/null | sort -V | tail -1)}"
BT="$SDK/build-tools/$BT_VERSION"
PLATFORM_VERSION="${COMPILE_SDK_VERSION:-$(ls "$SDK/platforms" 2>/dev/null | sort -V | tail -1)}"
AJ="$SDK/platforms/$PLATFORM_VERSION/android.jar"
[ -d "$BT" ] || { echo "错误：找不到 build-tools：$BT" >&2; exit 1; }
[ -f "$AJ" ] || { echo "错误：找不到 android.jar：$AJ" >&2; exit 1; }

JAVAC="${JAVAC:-$(command -v javac || true)}"
JAVA="${JAVA:-$(command -v java || true)}"
JAR="${JAR:-$(command -v jar || true)}"
KEYTOOL="${KEYTOOL:-$(command -v keytool || true)}"
if [ -z "$JAVAC" ] || [ -z "$JAVA" ] || [ -z "$JAR" ] || [ -z "$KEYTOOL" ]; then
  echo "错误：需要 JDK（javac/java/jar/keytool），请设置 JAVA_HOME 或把 JDK 加入 PATH" >&2
  exit 1
fi

MIN_SDK="${MIN_SDK:-30}"
TARGET_SDK="${TARGET_SDK:-35}"
OUT=build
STUB_JAR="$OUT/libxposed-api-stub.jar"

rm -rf "$OUT/mod_classes" "$OUT/mod_dex" "$OUT/apkroot"
mkdir -p "$OUT/mod_classes" "$OUT/mod_dex"

# 1) libxposed API 102：优先用外部 jar，否则用 stub/ 里的最小声明现场编译
if [ -n "${XPOSED_API_JAR:-}" ]; then
  cp "$XPOSED_API_JAR" "$STUB_JAR"
else
  rm -rf "$OUT/stub_classes"
  mkdir -p "$OUT/stub_classes"
  find stub -name '*.java' > "$OUT/stub_srcs.txt"
  "$JAVAC" -source 8 -target 8 -Xlint:-options -bootclasspath "$AJ" -d "$OUT/stub_classes" @"$OUT/stub_srcs.txt"
  ( cd "$OUT/stub_classes" && "$JAR" cf "../libxposed-api-stub.jar" . )
fi

# 2) 模块源码 -> class -> dex
find module/src -name '*.java' > "$OUT/srcs.txt"
"$JAVAC" -source 8 -target 8 -Xlint:-options -bootclasspath "$AJ" -cp "$STUB_JAR" -d "$OUT/mod_classes" @"$OUT/srcs.txt"
find "$OUT/mod_classes" -name '*.class' > "$OUT/classes.txt"
"$JAVA" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release --min-api "$MIN_SDK" --lib "$AJ" --output "$OUT/mod_dex" @"$OUT/classes.txt"

# 3) 资源 + manifest
"$BT/aapt2" compile --dir module/res -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/mod_unsigned.apk" -I "$AJ" --manifest module/AndroidManifest.xml "$OUT/res.zip" --auto-add-overlay --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" --version-code 1 --version-name 1.0

# 4) 组装 APK（dex + META-INF/xposed）
mkdir -p "$OUT/apkroot"
( cd "$OUT/apkroot" && unzip -oq ../mod_unsigned.apk )
cp "$OUT/mod_dex/classes.dex" "$OUT/apkroot/classes.dex"
mkdir -p "$OUT/apkroot/META-INF/xposed"
cp module/resources/META-INF/xposed/* "$OUT/apkroot/META-INF/xposed/"
rm -f "$OUT/apkroot/META-INF"/*.SF "$OUT/apkroot/META-INF"/*.RSA "$OUT/apkroot/META-INF/MANIFEST.MF"
( cd "$OUT/apkroot" && "$JAR" --create --no-manifest --file ../mod_signed_input.apk . )

# 5) 签名（keystore 不存在则自动生成，仅用于本地安装/调试）
KEYSTORE="${KEYSTORE:-$OUT/debug.keystore}"
KEYSTORE_PASS="${KEYSTORE_PASS:-android}"
KEY_ALIAS="${KEY_ALIAS:-amflyme}"
if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$(dirname "$KEYSTORE")"
  "$KEYTOOL" -genkeypair -keystore "$KEYSTORE" -storepass "$KEYSTORE_PASS" -keypass "$KEYSTORE_PASS" -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=AMFlymeLyric, O=AMFlymeLyric, C=CN" >/dev/null 2>&1
  echo "已生成调试签名：$KEYSTORE（alias=$KEY_ALIAS）"
fi

rm -f "$OUT/AMFlymeLyric.apk"
"$JAVA" -jar "$BT/lib/apksigner.jar" sign --ks "$KEYSTORE" --ks-pass "pass:$KEYSTORE_PASS" --key-pass "pass:$KEYSTORE_PASS" --ks-key-alias "$KEY_ALIAS" --out "$OUT/AMFlymeLyric.apk" "$OUT/mod_signed_input.apk"

echo "BUILD_OK $(stat -c %s "$OUT/AMFlymeLyric.apk" 2>/dev/null || wc -c < "$OUT/AMFlymeLyric.apk") $OUT/AMFlymeLyric.apk"
