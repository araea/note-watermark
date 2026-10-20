#!/data/data/com.termux/files/usr/bin/bash
# Publish to the module marketplace (Xposed-Modules-Repo/com.jy.notewatermark).
# Releases live only in the marketplace; the source repo carries none.
#
#   ./publish.sh                  push metadata, then publish the release for $TAG
#   ./publish.sh --metadata-only  push only SUMMARY/README/SOURCE_URL/icon (no release)
#   ./publish.sh --release-only   publish the release only (metadata already pushed)
#
# gh has no per-request timeout, and a connection that stalls through the proxy hangs
# it for good (4.3.0 sat for ten minutes after its APK had fully arrived). So the
# release goes out in separate steps - draft, upload, publish - each with a deadline
# and retries, each checking what GitHub actually holds before and after. Running the
# script again after any interruption picks up where it stopped; a published release
# whose APK matches is reported as done, not as an error.
set -euo pipefail
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
MP="$ROOT/marketplace"
GRADLE="$ROOT/build.gradle"
PKG=com.jy.notewatermark
ORG_REPO="Xposed-Modules-Repo/$PKG"
GH=${GH:-/data/data/com.termux/files/usr/bin/gh}
APK="$ROOT/build/NoteWatermark.apk"
ASSET=$(basename "$APK")
MODE=${1:-}

case $MODE in
  ""|--metadata-only|--release-only) ;;
  *) echo "usage: $0 [--metadata-only | --release-only]"; exit 2 ;;
esac

CODE=$(sed -n "s/.*versionCode \([0-9][0-9]*\).*/\1/p" "$GRADLE")
VERSION=$(sed -n "s/.*versionName '\([^']*\)'.*/\1/p" "$GRADLE")
TAG="$CODE-$VERSION"
NOTES="$MP/CHANGELOG-$VERSION.md"

if [ -z "$CODE" ] || [ -z "$VERSION" ]; then
  echo "cannot read versionCode/versionName from $GRADLE"
  exit 1
fi

if [ ! -f "$APK" ]; then
  echo "missing $APK - run ./build.sh first"
  exit 1
fi

if [ ! -f "$NOTES" ]; then
  echo "missing $NOTES"
  exit 1
fi

SHA=$(sha256sum "$APK" | cut -d' ' -f1)

# ------------------------------------------------------------------ helpers

# retry WHAT FUNCTION: up to three tries, a growing pause between them.
retry() {
  local what=$1 try
  for try in 1 2 3; do
    if "$2"; then return 0; fi
    echo "$what: try $try did not get through" >&2
    [ "$try" = 3 ] || sleep $((try * 5))
  done
  echo "$what: giving up; run the script again to continue from here" >&2
  return 1
}

# gh with a deadline: a stalled connection fails instead of hanging.
# PUBLISH_DEADLINE=<seconds> overrides every step's deadline (slow network, or tests).
gh_within() {
  local seconds=${PUBLISH_DEADLINE:-$1}
  shift
  timeout -k 10 "$seconds" "$GH" "$@"
}

# The release for $TAG as GitHub holds it: none / draft / published.
# Drafts have no git tag yet, so they are found in the list, not by tag.
# Fails when GitHub cannot be asked, so a lost answer is never read as "none".
release_state() {
  gh_within 60 api "repos/$ORG_REPO/releases?per_page=50" --jq \
    "[.[] | select(.tag_name == \"$TAG\")] | if length == 0 then \"none\" elif .[0].draft then \"draft\" else \"published\" end"
}

# The release's copy of the APK is complete and byte-identical to ours (GitHub's digest).
asset_matches() {
  local digest
  digest=$(gh_within 60 api "repos/$ORG_REPO/releases?per_page=50" --jq \
    "[.[] | select(.tag_name == \"$TAG\")][0].assets[]? | select(.name == \"$ASSET\" and .state == \"uploaded\") | .digest") || return 1
  [ "$digest" = "sha256:$SHA" ]
}

# ------------------------------------------------------------------ steps

check_repo() {
  gh_within 60 api "repos/$ORG_REPO" --jq .name >/dev/null
}

# Runs in a subshell for its own temp dir and trap. Every command is checked by hand:
# bash ignores `set -e` inside anything called from an `if`, which is how retry calls it.
push_metadata() (
  gh_within 60 api "repos/$ORG_REPO" -X PATCH -f description='素笺' >/dev/null || exit 1
  TMP=$(mktemp -d) || exit 1
  trap 'rm -rf "$TMP"' EXIT
  # Same reasoning as gh: let a dead ssh connection fail rather than wait forever.
  export GIT_SSH_COMMAND="ssh -o ConnectTimeout=20 -o ServerAliveInterval=15 -o ServerAliveCountMax=4"
  cd "$TMP" || exit 1
  timeout -k 10 120 git clone -q --depth=1 "git@github.com:$ORG_REPO.git" repo || exit 1
  cd repo || exit 1
  cp "$MP/SUMMARY" "$MP/README.md" "$MP/SOURCE_URL" "$MP/ic_launcher.png" . || exit 1
  git add SUMMARY README.md SOURCE_URL ic_launcher.png || exit 1
  if git diff --cached --quiet; then
    echo "Metadata already up to date"
  else
    git commit -q -m "Update marketplace metadata for note-watermark $VERSION" || exit 1
    timeout -k 10 120 git push -q origin HEAD || exit 1
    echo "Metadata pushed"
  fi
)

create_draft() {
  local state
  state=$(release_state) || return 1
  # A create that timed out may still have gone through: never make a second draft.
  if [ "$state" = none ]; then
    gh_within 90 release create "$TAG" --repo "$ORG_REPO" --draft \
      --title "$VERSION" --notes-file "$NOTES" >/dev/null || true
    state=$(release_state) || return 1
  fi
  [ "$state" != none ]
}

upload_apk() {
  asset_matches && return 0
  gh_within 600 release upload "$TAG" "$APK" --repo "$ORG_REPO" --clobber || true
  # The upload can land even when the reply never comes back; GitHub's digest decides.
  asset_matches
}

publish_release() {
  [ "$(release_state)" = published ] && return 0
  gh_within 90 release edit "$TAG" --repo "$ORG_REPO" --draft=false --latest \
    --title "$VERSION" --notes-file "$NOTES" >/dev/null || true
  [ "$(release_state)" = published ]
}

# ------------------------------------------------------------------ run

retry "reaching $ORG_REPO" check_repo || { echo "Marketplace repo not ready yet: $ORG_REPO"; exit 1; }

if [ "$MODE" != --metadata-only ]; then
  state=$(retry "reading release $TAG" release_state) || exit 1
  if [ "$state" = published ]; then
    if asset_matches; then
      echo "Release $TAG is already published with this APK (sha256 $SHA)"
      echo "https://github.com/$ORG_REPO/releases/tag/$TAG"
      exit 0
    fi
    echo "release $TAG is already published with a different $ASSET; bump the version instead"
    exit 1
  fi
fi

if [ "$MODE" != --release-only ]; then
  retry "pushing metadata" push_metadata
fi

if [ "$MODE" = --metadata-only ]; then
  echo "Metadata pushed for $TAG (no release created)"
  exit 0
fi

echo "APK sha256 $SHA"
retry "creating draft $TAG" create_draft
echo "Draft $TAG ready"
retry "uploading $ASSET" upload_apk
echo "$ASSET uploaded and matches (sha256 $SHA)"
retry "publishing $TAG" publish_release
echo "Published $TAG to https://github.com/$ORG_REPO/releases/tag/$TAG"
