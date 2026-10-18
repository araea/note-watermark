#!/data/data/com.termux/files/usr/bin/bash
# Desktop JVM checks for the editor-document converter; no device needed.
set -euo pipefail
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OUT="$ROOT/build/tiptap-check"
ANDROID_JAR=/data/data/com.termux/files/home/android/platform/android-35/android.jar
rm -rf "$OUT" && mkdir -p "$OUT"
javac -classpath "$ANDROID_JAR" -encoding UTF-8 -nowarn -d "$OUT" \
  "$ROOT/src/com/jy/notewatermark/Tiptap.java" "$ROOT/src/com/jy/notewatermark/ExportKit.java" \
  "$ROOT/tests/TiptapCheck.java"
java -cp "$OUT:$ANDROID_JAR" com.jy.notewatermark.TiptapCheck
