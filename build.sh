#!/usr/bin/env bash
# Stable Material Components and AndroidX are resolved by the pinned Gradle wrapper.
set -euo pipefail
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$ROOT"
args=(assembleRelease --no-daemon)
# Android/Termux needs a native ARM aapt2 instead of the desktop Maven executable.
if [ -n "${TERMUX_VERSION:-}" ]; then
  export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.native=false"
  args+=("-Pandroid.aapt2FromMavenOverride=$(command -v aapt2)")
fi
./gradlew "${args[@]}"
KS="$ROOT/keystore/note-watermark.keystore"
mkdir -p "$(dirname "$KS")"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias notewm -storepass notewm123 -keypass notewm123 \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=NoteWatermark"
fi
# Keep the existing module signing identity for seamless updates.
SIGNER=${APKSIGNER:-apksigner}
"$SIGNER" sign --ks "$KS" --ks-pass pass:notewm123 --key-pass pass:notewm123 \
  --out "$ROOT/build/NoteWatermark.apk" "$ROOT/build/outputs/apk/release/NoteWatermark-release-unsigned.apk"
"$SIGNER" verify "$ROOT/build/NoteWatermark.apk"
ls -lh "$ROOT/build/NoteWatermark.apk"
