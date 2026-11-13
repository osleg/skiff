#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_JAR:?Set ANDROID_JAR to the official API 35 android.jar}"
: "${ANDROID_BUILD_TOOLS:?Set ANDROID_BUILD_TOOLS to Android build-tools 35.0.0}"
JAVA=${JAVA:-java}
KEYTOOL=${KEYTOOL:-keytool}
OUT=build/manual
mkdir -p "$OUT/classes" "$OUT/dex"
find app/src/main/java -name '*.java' | LC_ALL=C sort > "$OUT/sources.txt"
"$JAVA" com.sun.tools.javac.Main -encoding UTF-8 -source 8 -target 8 -classpath "$ANDROID_JAR" -Xlint:all -d "$OUT/classes" @"$OUT/sources.txt"
find "$OUT/classes" -name '*.class' | LC_ALL=C sort > "$OUT/classes.txt"
"$JAVA" -cp "$ANDROID_BUILD_TOOLS/lib/d8.jar" com.android.tools.r8.D8 --min-api 26 --lib "$ANDROID_JAR" --output "$OUT/dex" @"$OUT/classes.txt"
"$ANDROID_BUILD_TOOLS/aapt2" link -I "$ANDROID_JAR" --manifest app/src/main/AndroidManifest.xml -o "$OUT/resources.apk"
python3 - "$OUT" <<'PY'
import pathlib,sys,zipfile
p=pathlib.Path(sys.argv[1])
with zipfile.ZipFile(p/'resources.apk') as source, zipfile.ZipFile(p/'unsigned.apk','w',zipfile.ZIP_DEFLATED) as out:
    for i in source.infolist(): out.writestr(i,source.read(i.filename))
    for f in sorted((p/'dex').glob('*.dex')): out.write(f, f.name)
PY
"$ANDROID_BUILD_TOOLS/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
# This local debug key is deliberately outside source/artifact bundles. Never use it for release publishing.
KEY=${CAFA_DEBUG_KEY:-$HOME/.android/cafa-debug.keystore}
if [[ ! -f "$KEY" ]]; then
    mkdir -p "$(dirname "$KEY")"
    "$KEYTOOL" -genkeypair -keystore "$KEY" -storepass android -keypass android -alias androiddebugkey -dname 'CN=Android Debug,O=Android,C=US' -keyalg RSA -keysize 2048 -validity 10000
fi
"$JAVA" -jar "$ANDROID_BUILD_TOOLS/lib/apksigner.jar" sign --ks "$KEY" --ks-pass pass:android --key-pass pass:android --out "$OUT/cafa-debug.apk" "$OUT/aligned.apk"
"$JAVA" -jar "$ANDROID_BUILD_TOOLS/lib/apksigner.jar" verify --verbose "$OUT/cafa-debug.apk"
sha256sum "$OUT/cafa-debug.apk"
