#!/data/data/com.termux/files/usr/bin/bash
# Push marketplace metadata + release to Xposed-Modules-Repo/com.jy.notewatermark
set -euo pipefail
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
MP="$ROOT/marketplace"
PKG=com.jy.notewatermark
ORG_REPO="Xposed-Modules-Repo/$PKG"
GH=/data/data/com.termux/files/usr/bin/gh
APK="$ROOT/build/NoteWatermark.apk"
VERSION="2.2"
TAG="4-$VERSION"

if [ ! -f "$APK" ]; then
  echo "missing $APK - run ./build.sh first"
  exit 1
fi

if ! "$GH" api "repos/$ORG_REPO" --jq .name >/dev/null 2>&1; then
  echo "Marketplace repo not ready yet: $ORG_REPO"
  exit 1
fi

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
cd "$TMP"
git clone --depth=1 "git@github.com:$ORG_REPO.git" repo
cd repo
cp "$MP/SUMMARY" "$MP/README.md" "$MP/SOURCE_URL" "$MP/ic_launcher.png" .
git add SUMMARY README.md SOURCE_URL ic_launcher.png
if git diff --cached --quiet; then
  echo "Metadata already up to date"
else
  git commit -m "Update marketplace metadata for note-watermark $VERSION"
  git push origin HEAD
fi

"$GH" release create "$TAG" "$APK" \
  --repo "$ORG_REPO" \
  --title "$VERSION" \
  --notes-file "$MP/CHANGELOG-$VERSION.md"

echo "Published to https://github.com/$ORG_REPO"
