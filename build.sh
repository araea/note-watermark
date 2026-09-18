#!/data/data/com.termux/files/usr/bin/bash
# NOTE: libs/r8.jar is gitignored. After a fresh clone, download it once:
#   curl -fsSL -o libs/r8.jar https://maven.google.com/com/android/tools/r8/8.9.35/r8-8.9.35.jar
# libs/libxposed-api-102.jar is gitignored too; this script fetches it from Maven Central.
set -e
R="$(cd "$(dirname "$0")" && pwd)"
ANDROID_JAR=/data/data/com.termux/files/home/android/platform/android-35/android.jar
BT=/data/data/com.termux/files/home/android/android-sdk-tools/build-tools
AAPT=$BT/aapt
ZIPALIGN=$BT/zipalign
R8=$R/libs/r8.jar
FRAMEWORK=/system/framework/framework-res.apk
# Kept outside build/ so wiping build output cannot destroy the signing key:
# losing it forces every user of the module to uninstall and reinstall.
KS=$R/keystore/note-watermark.keystore
OUT=$R/build
APK_UNSIGNED=$OUT/note-watermark.unsigned.apk
APK=$OUT/NoteWatermark.apk

# Modern Xposed API (io.github.libxposed:api). Compile-only: the framework
# provides these classes at runtime, so the jar is used for javac and as a D8
# library, never fed into the dex.
LIBXPOSED=$R/libs/libxposed-api-102.jar
LIBXPOSED_URL=https://repo.maven.apache.org/maven2/io/github/libxposed/api/102.0.0/api-102.0.0.aar

if [ ! -f "$R8" ]; then
  echo "missing $R8"
  echo "curl -fsSL -o libs/r8.jar https://maven.google.com/com/android/tools/r8/8.9.35/r8-8.9.35.jar"
  exit 1
fi

if [ ! -f "$LIBXPOSED" ]; then
  echo "== 0. fetch libxposed api 102 =="
  TMPAAR=$(mktemp -d)
  curl -fsSL -o "$TMPAAR/api.aar" "$LIBXPOSED_URL"
  ( cd "$TMPAAR" && unzip -o -q api.aar classes.jar )
  mv "$TMPAAR/classes.jar" "$LIBXPOSED"
  rm -rf "$TMPAAR"
fi

echo "== 1. javac =="
rm -rf $OUT/classes && mkdir -p $OUT/classes
find $R/src -name '*.java' > $OUT/sources.txt
javac -classpath $ANDROID_JAR:$LIBXPOSED -source 8 -target 8 -encoding UTF-8 \
  -nowarn -d $OUT/classes @$OUT/sources.txt
echo "   compiled $(find $OUT/classes -name '*.class' | wc -l) classes"

echo "== 2. d8 -> dex =="
rm -rf $OUT/dex && mkdir -p $OUT/dex
# libxposed is passed with --lib, exactly like android.jar: it resolves the
# module's references without being packaged into the dex.
find $OUT/classes -name '*.class' > $OUT/classlist.txt
java -cp $R8 com.android.tools.r8.D8 --release --min-api 26 \
  --lib $ANDROID_JAR --lib $LIBXPOSED --output $OUT/dex @$OUT/classlist.txt
echo "   dex: $(ls -la $OUT/dex/classes.dex | awk '{print $5}') bytes"

echo "== 3. aapt package =="
$AAPT package -f -M $R/AndroidManifest.xml -I $FRAMEWORK -S $R/res -F $APK_UNSIGNED
( cd $OUT/dex && $AAPT add $APK_UNSIGNED classes.dex >/dev/null )
# The modern API reads its entry list, scope and properties from META-INF/xposed/
# at the APK root; aapt -A would only reach assets/.
( cd $R/resources && $AAPT add $APK_UNSIGNED \
    META-INF/xposed/java_init.list \
    META-INF/xposed/module.prop \
    META-INF/xposed/scope.list >/dev/null )

echo "== 4. keystore (generate once) =="
mkdir -p $(dirname $KS)
if [ ! -f $KS ]; then
  keytool -genkeypair -keystore $KS -alias notewm -storepass notewm123 -keypass notewm123 \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=NoteWatermark" >/dev/null 2>&1
  echo "   generated keystore"
fi

echo "== 5. zipalign + sign =="
rm -f $APK
$ZIPALIGN -f -p 4 $APK_UNSIGNED $OUT/note-watermark.aligned.apk
apksigner sign --ks $KS --ks-pass pass:notewm123 --key-pass pass:notewm123 \
  --out $APK $OUT/note-watermark.aligned.apk
echo "== DONE =="
ls -la $APK
