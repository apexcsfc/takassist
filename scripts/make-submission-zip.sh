#!/usr/bin/env bash
# Builds the source zip for TAK.gov's third-party plugin signing (tak.gov/user_builds),
# then optionally proves it builds from a clean unzip, the way their servers will.
#
#   scripts/make-submission-zip.sh          # zip only
#   scripts/make-submission-zip.sh --test   # zip, unzip to a temp folder, assembleCivRelease
#
# The point-of-contact address goes into the README inside the zip only. Put it in
# ~/.config/takassist/contact.txt (one line) or pass TAKASSIST_CONTACT=you@example.com.
# Output: ~/Downloads/LLMAssist-<plugin version>-<ATAK version>.zip

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NAME="TAKAssist"
OUTDIR="${OUTDIR:-$HOME/Downloads}"
mkdir -p "$OUTDIR" && OUTDIR="$(cd "$OUTDIR" && pwd)"
TEST=0
NO_NATIVE=0
for arg in "$@"; do
  case "$arg" in
    --test) TEST=1 ;;
    # Diagnostic: leave out the bundled model server (jniLibs). The plugin builds and
    # loads but can't start a model; used to test whether native files trip TAK.gov's signing.
    --no-native) NO_NATIVE=1 ;;
  esac
done

VER=$(sed -n 's/.*ext.PLUGIN_VERSION *= *"\(.*\)".*/\1/p' "$ROOT/app/build.gradle" | head -1)
ATAK=$(sed -n "s/.*ext.ATAK_VERSION *= *'\(.*\)'.*/\1/p" "$ROOT/app/build.gradle" | head -1)
[ -n "$VER" ] && [ -n "$ATAK" ] || { echo "Couldn't read PLUGIN_VERSION / ATAK_VERSION from app/build.gradle" >&2; exit 1; }

CONTACT="${TAKASSIST_CONTACT:-}"
[ -z "$CONTACT" ] && [ -f "$HOME/.config/takassist/contact.txt" ] && CONTACT=$(head -1 "$HOME/.config/takassist/contact.txt")
if [ -z "$CONTACT" ]; then
  echo "No contact address. TAK.gov requires one in the README. Either:" >&2
  echo "  mkdir -p ~/.config/takassist && echo you@example.com > ~/.config/takassist/contact.txt" >&2
  echo "  or run with TAKASSIST_CONTACT=you@example.com" >&2
  exit 1
fi

ZIP="$OUTDIR/$NAME-$VER-$ATAK.zip"
[ $NO_NATIVE -eq 1 ] && ZIP="$OUTDIR/$NAME-$VER-$ATAK-nonative.zip"
if [ -f "$ZIP" ]; then
  echo "$ZIP already exists. Bump PLUGIN_VERSION in app/build.gradle for a new submission" >&2
  echo "(a resubmission with the same version won't install as an update), or delete the zip." >&2
  exit 1
fi

STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT
echo "== Staging $NAME $VER for ATAK $ATAK"
mkdir -p "$STAGE/$NAME"
# tar rather than rsync so the same command works on macOS and Linux.
(cd "$ROOT" && tar cf - \
  --exclude '.git' --exclude '.gradle' --exclude '.idea' --exclude '*.iml' \
  --exclude 'build' --exclude 'build-snapdragon' --exclude '.takdev' --exclude './app/libs' \
  --exclude 'local.properties' --exclude 'local.properties.*' --exclude '*.bak' --exclude '*~' --exclude '*.apk' --exclude '*.aab' --exclude '*.zip' \
  --exclude '*.keystore' --exclude '*.jks' --exclude 'android_keystore' \
  --exclude '.DS_Store' --exclude '__pycache__' --exclude 'slim-log-*.txt' \
  --exclude './app/src/main/assets/usermanual.pdf' .) | (cd "$STAGE/$NAME" && tar xf -)

if [ $NO_NATIVE -eq 1 ]; then
  rm -rf "$STAGE/$NAME/app/src/main/jniLibs"
  echo "   (no-native test build: bundled model server left out)"
fi

# Contact address: only in the zipped README, never in the project's own files.
sed -i.bak "s|CONTACT_EMAIL|$CONTACT|" "$STAGE/$NAME/README.md" && rm "$STAGE/$NAME/README.md.bak"

# Checks: things that make a build fail at TAK.gov without saying why.
fail=0
[ -f "$STAGE/$NAME/gradle/wrapper/gradle-wrapper.jar" ] || { echo "MISSING gradle/wrapper/gradle-wrapper.jar"; fail=1; }
[ -f "$STAGE/$NAME/template.local.properties" ] || { echo "MISSING template.local.properties"; fail=1; }
grep -q "com.atakmap.app.component" "$STAGE/$NAME/app/src/main/AndroidManifest.xml" || { echo "MISSING discovery activity in the manifest"; fail=1; }
if grep -rIl --exclude-dir=jniLibs -e '/Users/' -e '/home/' "$STAGE/$NAME" | grep -v -e '\.md$' -e '/scripts/' ; then
  echo "FOUND absolute local paths in the files above"; fail=1
fi
[ $fail -eq 0 ] || { echo "Fix the problems above, then run again." >&2; exit 1; }

mkdir -p "$OUTDIR"
(cd "$STAGE" && zip -qr "$ZIP" "$NAME")
echo "== Wrote $ZIP ($(du -h "$ZIP" | cut -f1))"
echo "   Native libraries: $(du -sh "$STAGE/$NAME/app/src/main/jniLibs" 2>/dev/null | cut -f1 || echo none)"

if [ $TEST -eq 1 ]; then
  echo "== Clean build test (unzip + assembleCivRelease)"
  # The SDK's build plugin finds its signing key and jars two folders up from the
  # project (the SDK root), so the test copy goes next to this one in samples/.
  T=$(mktemp -d)
  TEST_DIR="$(cd "$ROOT/.." && pwd)/$NAME-ziptest"
  rm -rf "$TEST_DIR"
  (cd "$T" && unzip -q "$ZIP") && mv "$T/$NAME" "$TEST_DIR" && rm -rf "$T"
  # Reuse this machine's SDK paths for the test build only.
  cp "$ROOT/local.properties" "$TEST_DIR/local.properties"
  if (cd "$TEST_DIR" && ./gradlew -q assembleCivRelease); then
    find "$TEST_DIR/app/build/outputs/apk" -name '*.apk' -exec ls -lh {} \;
    echo "== Clean build passed."
    rm -rf "$TEST_DIR"
  else
    echo "== Clean build FAILED. Read the errors above; the test copy is in $TEST_DIR" >&2
    exit 1
  fi
fi

echo
echo "Next: log in to tak.gov, open the user builds page, and drop $ZIP onto it."
