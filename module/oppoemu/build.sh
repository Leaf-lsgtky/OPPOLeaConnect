#!/usr/bin/env bash
# 构建 OppoEmu LSPosed 模块（只用本机 Android SDK，无需网络）
set -euo pipefail
cd "$(dirname "$0")"

SDK=/e/Android/Sdk
BT=$SDK/build-tools/37.0.0
AJAR=$SDK/platforms/android-36/android.jar
DIR="$(pwd)"
OUT=build
CLASSES=$OUT/classes
DEX=$OUT/dex
KEY=$DIR/keystore/oppoemu.keystore
MINSDK=26
TGTSDK=34
VERCODE=78
VERNAME=6.18

rm -rf "$OUT"; mkdir -p "$CLASSES" "$DEX"

echo "[1/6] javac"
javac -nowarn -encoding UTF-8 -source 8 -target 8 -bootclasspath "$AJAR" -d "$CLASSES" \
    $(find src/de src/io -name '*.java')

echo "[2/6] d8 (只打 io/qoder，xposed 桩留编译期)"
"$BT/d8.bat" --release --min-api "$MINSDK" --lib "$AJAR" --output "$DEX" \
    $(find "$CLASSES/io" -name '*.class')

echo "[3/6] aapt2 compile + link (-A 带进 assets/xposed_init)"
"$BT/aapt2.exe" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2.exe" link -o "$OUT/unsigned.apk" -I "$AJAR" -A assets \
    --min-sdk-version "$MINSDK" --target-sdk-version "$TGTSDK" \
    --version-code "$VERCODE" --version-name "$VERNAME" --replace-version \
    --manifest AndroidManifest.xml "$OUT/res.zip"

echo "[4/6] 塞入 classes.dex + META-INF/xposed/*"
cp "$DEX/classes.dex" "$OUT/"
jar uMf "$OUT/unsigned.apk" -C "$OUT" classes.dex
jar uMf "$OUT/unsigned.apk" -C meta-info META-INF/xposed

echo "[5/6] zipalign"
"$BT/zipalign.exe" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[6/6] apksigner v1+v2+v3"
[ -f "$KEY" ] || keytool -genkeypair -v -keystore "$KEY" -storepass android -keypass android \
    -alias oppoemu -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=OppoEmu, OU=dev, O=dev, L=NA, S=NA, C=NA" >/dev/null 2>&1
"$BT/apksigner.bat" sign --ks "$KEY" --ks-pass pass:android --key-pass pass:android \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --out OppoEmu.apk "$OUT/aligned.apk" 2>&1 | grep -v "WARNING" || true

echo
echo "产物: $(pwd)/OppoEmu.apk"
