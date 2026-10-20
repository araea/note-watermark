#!/data/data/com.termux/files/usr/bin/bash
# Device design checks, start to finish: build the test runner, run it against the
# installed module, bring the review screenshots back to build/design-tests/design-review.
#
#   tests/run.sh             run on the default display
#   tests/run.sh <display>   run on another display, e.g. a virtual one, to keep the
#                            phone's own screen free
#
# Needs root (install, instrument, pull) and the module installed from the current build.
# Whatever happens, the runner package and the screenshots inside the app are removed on
# the way out, so neither is left behind on the phone.
set -euo pipefail
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OUT="$ROOT/build/design-tests"
PKG=com.jy.notewatermark
REVIEW=/data/data/$PKG/files/design-review
DISPLAY_ARG=${1:+-e display $1}

bash "$ROOT/tests/build.sh"

trap 'su -c "pm uninstall $PKG.test; rm -rf $REVIEW" >/dev/null 2>&1 || true' EXIT
su -c "cp '$OUT/DesignTests.apk' /data/local/tmp/notewm-tests.apk && pm install -r /data/local/tmp/notewm-tests.apk >/dev/null; rm -f /data/local/tmp/notewm-tests.apk"

# Bounded: a test process ColorOS freezes in the background would otherwise wait forever.
su -c "timeout 600 am instrument -w $DISPLAY_ARG $PKG.test/.DesignSmoke" > "$OUT/instrument.log" || true
grep -vE "^  ok " "$OUT/instrument.log" || true
grep -q "PASS: expressive settings UI" "$OUT/instrument.log" \
    || { echo "design checks did not pass; see $OUT/instrument.log" >&2; exit 1; }
echo "$(grep -c '^  ok ' "$OUT/instrument.log") checks passed"

mkdir -p "$OUT/design-review"
for f in $(su -c "ls $REVIEW"); do
  su -c "cat $REVIEW/$f" > "$OUT/design-review/$f"
done
echo "screenshots in $OUT/design-review"
