#!/usr/bin/env bash
# PiLiPala 扫码 构建脚本（不需要 Gradle）
#
# 依赖：JDK 11+、Android SDK（build-tools 34 + platform android-34）
#   export ANDROID_HOME=/path/to/android-sdk
#   ./build.sh
set -e

PROJ="$(cd "$(dirname "$0")" && pwd)"
ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
BT="${BT:-$ANDROID_HOME/build-tools/34.0.0}"
ANDROID_JAR="${ANDROID_JAR:-$ANDROID_HOME/platforms/android-34/android.jar}"

if [ ! -f "$ANDROID_JAR" ]; then
  echo "找不到 android.jar：$ANDROID_JAR（先装 platform 34，或用 ANDROID_JAR 指过去）" >&2
  exit 1
fi
if [ ! -x "$BT/d8" ]; then
  echo "找不到 build-tools：$BT（用 BT 环境变量指定）" >&2
  exit 1
fi

mkdir -p "$PROJ/libs"
if [ ! -f "$PROJ/libs/zxing-core-3.5.3.jar" ]; then
  echo "下载 ZXing core…"
  curl -sL -o "$PROJ/libs/zxing-core-3.5.3.jar" \
    https://repo1.maven.org/maven2/com/google/zxing/core/3.5.3/core-3.5.3.jar
fi

rm -rf "$PROJ/build"
mkdir -p "$PROJ/build/classes" "$PROJ/build/dex" "$PROJ/build/apk" "$PROJ/build/res"

echo "[1/7] javac"
find "$PROJ/src" "$PROJ/stubs" -name '*.java' > "$PROJ/build/sources.txt"
javac --release 8 -encoding UTF-8 \
      -cp "$ANDROID_JAR:$PROJ/libs/zxing-core-3.5.3.jar" \
      -d "$PROJ/build/classes" @"$PROJ/build/sources.txt"

echo "[2/7] Xposed 桩类单独打 lib jar（运行时由框架提供，不进 dex）"
jar cf "$PROJ/build/stubs.jar" -C "$PROJ/build/classes" de
rm -rf "$PROJ/build/classes/de"

echo "[3/7] d8（含 ZXing core）"
jar cf "$PROJ/build/classes.jar" -C "$PROJ/build/classes" .
"$BT/d8" --min-api 21 \
   --lib "$ANDROID_JAR" \
   --lib "$PROJ/build/stubs.jar" \
   --output "$PROJ/build/dex" \
   "$PROJ/build/classes.jar" "$PROJ/libs/zxing-core-3.5.3.jar"

echo "[4/7] aapt2 compile res"
"$BT/aapt2" compile --dir "$PROJ/res" -o "$PROJ/build/res/compiled.zip"

echo "[5/7] aapt2 link"
"$BT/aapt2" link -o "$PROJ/build/apk/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$PROJ/AndroidManifest.xml" \
    -R "$PROJ/build/res/compiled.zip" \
    --auto-add-overlay \
    --min-sdk-version 21 --target-sdk-version 33 \
    --version-code 1 --version-name 1.0

echo "[6/7] 打包 dex / assets / module.prop"
python3 - "$PROJ" <<'PY'
import sys, os, zipfile
proj = sys.argv[1]
base = os.path.join(proj, 'build/apk/base.apk')
tmp  = os.path.join(proj, 'build/apk/unsigned.apk')
src = zipfile.ZipFile(base, 'r')
out = zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED)
for item in src.infolist():
    out.writestr(item, src.read(item.filename))
src.close()
out.write(os.path.join(proj, 'build/dex/classes.dex'), 'classes.dex')
out.write(os.path.join(proj, 'assets/xposed_init'), 'assets/xposed_init')
out.write(os.path.join(proj, 'META-INF/xposed/module.prop'), 'META-INF/xposed/module.prop')
out.close()
PY

echo "[7/7] zipalign + 签名"
"$BT/zipalign" -f 4 "$PROJ/build/apk/unsigned.apk" "$PROJ/build/apk/aligned.apk"

KS="$PROJ/build/debug.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias pilipala_scan -keyalg RSA -keysize 2048 -validity 3650 \
    -dname "CN=PiLiPalaScan, OU=mod, O=mod, L=CN, ST=CN, C=CN" >/dev/null 2>&1
fi

"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out "$PROJ/PiLiPala扫码-1.0.apk" "$PROJ/build/apk/aligned.apk"

"$BT/apksigner" verify --min-sdk-version 21 "$PROJ/PiLiPala扫码-1.0.apk"
ls -lh "$PROJ/PiLiPala扫码-1.0.apk"
