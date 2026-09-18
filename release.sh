#!/usr/bin/env bash
# Cuts a release: version bump -> rebuilds -> verification -> wheel -> tag (T-142).
#
#   ./release.sh 1.13.0 [T-147] [--notes FILE]
#
# The second argument is the gittoc ticket for the release itself, referenced in
# the commit subject the way every other change here is.
#
# Exists because the procedure was README prose plus whatever the person cutting
# the release remembered. Every part of it has a silent failure mode: a stale web
# bundle or APK ships the PREVIOUS build inside a wheel labelled with the new
# version, and nothing downstream notices. So this rebuilds both, then proves the
# APK it embedded is the one it just built and is signed with the same key as the
# one it replaced.
#
# What it deliberately does NOT do: anything involving a remote. It stops at the
# annotated tag.
set -uo pipefail
cd "$(dirname "$0")"

VERSION="${1:-}"
TICKET="${2:-}"
NOTES_FILE=""
if [ "${2:-}" = "--notes" ]; then TICKET=""; NOTES_FILE="${3:-}"; fi
if [ "${3:-}" = "--notes" ]; then NOTES_FILE="${4:-}"; fi

die() { echo "release: $*" >&2; exit 1; }
step() { echo; echo "=================== $* ==================="; }

[ -n "$VERSION" ] || die "usage: ./release.sh <version> [T-nnn] [--notes FILE]"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "version must be MAJOR.MINOR.PATCH, got '$VERSION'"
if [ -n "$TICKET" ] && [[ ! "$TICKET" =~ ^T-[0-9]+$ ]]; then
  die "ticket must look like T-147, got '$TICKET'"
fi
if [ -n "$NOTES_FILE" ] && [ ! -f "$NOTES_FILE" ]; then die "notes file not found: $NOTES_FILE"; fi

# ---------------------------------------------------------------------------
# Preconditions. All of them before anything is written, so a refusal leaves the
# tree exactly as it was.
# ---------------------------------------------------------------------------
step "checking preconditions"

BRANCH=$(git rev-parse --abbrev-ref HEAD)
[ "$BRANCH" = "main" ] || die "on branch '$BRANCH' — releases are cut from main"
[ -z "$(git status --porcelain)" ] || die "working tree is dirty — commit or stash first"
git rev-parse -q --verify "refs/tags/v$VERSION" >/dev/null && die "tag v$VERSION already exists"

CURRENT_VERSION=$(sed -n 's/^version = "\(.*\)"/\1/p' server/pyproject.toml | head -1)
CURRENT_CODE=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' android/app/build.gradle.kts | head -1)
[ -n "$CURRENT_VERSION" ] || die "could not read the current version from server/pyproject.toml"
[ -n "$CURRENT_CODE" ] || die "could not read versionCode from android/app/build.gradle.kts"
[ "$CURRENT_VERSION" != "$VERSION" ] || die "server/pyproject.toml is already at $VERSION"
NEXT_CODE=$((CURRENT_CODE + 1))

[ -f android/keystore.properties ] || die "android/keystore.properties missing — a release build cannot be signed"
command -v npm >/dev/null || die "npm not found — the web bundle has to be rebuilt"

# aapt2 is probed by RUNNING it, not by existence: an SDK can carry a build-tools
# binary for the wrong architecture, which fails only when executed.
find_tool() {
  local name="$1" override="$2" candidates=() sdk c
  [ -n "$override" ] && candidates+=("$override")
  command -v "$name" >/dev/null 2>&1 && candidates+=("$(command -v "$name")")
  sdk=$(sed -n 's/^sdk\.dir=//p' android/local.properties 2>/dev/null | head -1)
  sdk="${sdk:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}}"
  if [ -n "$sdk" ]; then
    while IFS= read -r c; do candidates+=("$c"); done < <(ls -1 "$sdk"/build-tools/*/"$name" 2>/dev/null | sort -Vr)
  fi
  for c in "${candidates[@]}"; do
    if "$c" version >/dev/null 2>&1; then echo "$c"; return 0; fi
  done
  return 1
}

NO_TOOL_HINT="Looked on PATH and in <sdk>/build-tools/*/. A binary that exists but
  cannot execute (an SDK downloaded for a different CPU architecture) is skipped
  by design, which is why this can fail on a machine that appears to have one."
AAPT2_BIN=$(find_tool aapt2 "${AAPT2:-}") ||
  die "no working aapt2 found — set AAPT2=/path/to/aapt2.
  $NO_TOOL_HINT"
APKSIGNER_BIN=$(find_tool apksigner "${APKSIGNER:-}") ||
  die "no working apksigner found — set APKSIGNER=/path/to/apksigner.
  $NO_TOOL_HINT"

EMBEDDED_APK=server/src/shoppinglist_server/apk/shoppinglist.apk
PREVIOUS_CERT=""
if [ -f "$EMBEDDED_APK" ]; then
  PREVIOUS_CERT=$("$APKSIGNER_BIN" verify --print-certs "$EMBEDDED_APK" 2>/dev/null |
    sed -n 's/.*certificate SHA-256 digest: //p' | head -1)
fi

echo "  version     $CURRENT_VERSION -> $VERSION"
echo "  versionCode $CURRENT_CODE -> $NEXT_CODE"
echo "  ticket      ${TICKET:-(none given)}"
echo "  aapt2       $AAPT2_BIN"
echo "  apksigner   $APKSIGNER_BIN"

# ---------------------------------------------------------------------------
# Version bump. One version across every part, which is what makes the wheel a
# single artifact — see "Versioning and releases" in the README.
# ---------------------------------------------------------------------------
step "bumping versions"

python3 - "$VERSION" "$NEXT_CODE" <<'PY' || die "version bump failed"
import io, re, sys

version, code = sys.argv[1], sys.argv[2]

def edit(path, pattern, replacement):
    src = io.open(path, encoding="utf-8").read()
    out, n = re.subn(pattern, replacement, src, count=1)
    if n != 1:
        raise SystemExit(f"release: could not bump {path} (matched {n} times)")
    io.open(path, "w", encoding="utf-8").write(out)
    print(f"  {path}")

edit("server/pyproject.toml", r'(?m)^version = "[^"]*"', f'version = "{version}"')
edit("android/app/build.gradle.kts", r"versionCode = \d+", f"versionCode = {code}")
edit("android/app/build.gradle.kts", r'versionName = "[^"]*"', f'versionName = "{version}"')
# web/ is not published to npm, but the README promises one version for every
# part, and a package.json frozen at 0.0.0 quietly made that untrue.
edit("web/package.json", r'(?m)^  "version": "[^"]*"', f'  "version": "{version}"')
# npm rewrites package-lock.json's version fields to match package.json on the
# next `npm install` regardless of who edited package.json, so a lockfile left
# behind at 0.0.0 dirties the tree the moment anyone installs (T-201). Bump it
# here too, anchored on indentation so only the top-of-file "name": "web" pair
# and the packages[""] "name": "web" pair are touched, never a dependency that
# happens to also be named "web".
edit(
    "web/package-lock.json",
    r'(?m)^(  "name": "web",\n  "version": ")[^"]*(")',
    rf'\g<1>{version}\g<2>',
)
edit(
    "web/package-lock.json",
    r'(?m)^(      "name": "web",\n      "version": ")[^"]*(")',
    rf'\g<1>{version}\g<2>',
)
PY

# ---------------------------------------------------------------------------
# Rebuild the committed inputs. build-wheel.sh does not do this, on purpose, and
# skipping either one is how a wheel ends up shipping the previous build.
# ---------------------------------------------------------------------------
step "rebuilding the web bundle"
( cd web && npm run build ) || die "web build failed"

step "verifying everything"
# Before assembleRelease, not after: this is the slow, fail-fast gate, and there
# is no point signing an APK for a tree whose tests are red.
./verify-all.sh || die "verification failed"

step "building the signed release APK"
# Same gradle invocation family as verify-all's, so the daemon it just warmed is
# reused rather than a second one being started next to it — this machine is
# memory-tight. The daemon is stopped immediately afterwards.
( cd android && ./gradlew :app:assembleRelease --offline )
APK_STATUS=$?
( cd android && ./gradlew --stop >/dev/null 2>&1 )
[ $APK_STATUS -eq 0 ] || die "assembleRelease failed"

BUILT_APK=android/app/build/outputs/apk/release/app-release.apk
[ -f "$BUILT_APK" ] || die "no APK at $BUILT_APK"

# ---------------------------------------------------------------------------
# Prove the APK is the one just built, before it is embedded. A copy step that
# silently used a stale file would otherwise be invisible until someone installed
# the app and found the old version.
# ---------------------------------------------------------------------------
step "checking the APK"

BADGING=$("$AAPT2_BIN" dump badging "$BUILT_APK" 2>/dev/null) || die "aapt2 could not read the APK"
APK_NAME=$(echo "$BADGING" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)
APK_CODE=$(echo "$BADGING" | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p" | head -1)
[ "$APK_NAME" = "$VERSION" ] || die "APK versionName is '$APK_NAME', expected '$VERSION'"
[ "$APK_CODE" = "$NEXT_CODE" ] || die "APK versionCode is '$APK_CODE', expected '$NEXT_CODE'"
echo "  versionName $APK_NAME, versionCode $APK_CODE"

"$APKSIGNER_BIN" verify "$BUILT_APK" >/dev/null 2>&1 || die "the built APK is not validly signed"
NEW_CERT=$("$APKSIGNER_BIN" verify --print-certs "$BUILT_APK" 2>/dev/null |
  sed -n 's/.*certificate SHA-256 digest: //p' | head -1)
[ -n "$NEW_CERT" ] || die "could not read the signing certificate of the built APK"
if [ -n "$PREVIOUS_CERT" ] && [ "$NEW_CERT" != "$PREVIOUS_CERT" ]; then
  # Android refuses to update an installed app across a change of signing key, so
  # this would strand every existing user on the version they already have.
  die "signing key CHANGED from the previous release
  was $PREVIOUS_CERT
  now $NEW_CERT"
fi
echo "  signed, same key as the previous release"

cp "$BUILT_APK" "$EMBEDDED_APK" || die "could not embed the APK"
echo "  embedded $(du -h "$EMBEDDED_APK" | cut -f1) at $EMBEDDED_APK"

# ---------------------------------------------------------------------------
step "building the wheel"
./build-wheel.sh || die "wheel build failed"

step "smoke-testing the wheel"
./smoke-wheel.sh "" "$VERSION" || die "wheel smoke test failed"

# ---------------------------------------------------------------------------
step "committing and tagging"

LAST_TAG=$(git describe --tags --abbrev=0 2>/dev/null)
if [ -n "$NOTES_FILE" ]; then
  NOTES=$(cat "$NOTES_FILE")
elif [ -n "$LAST_TAG" ]; then
  NOTES="Changes since $LAST_TAG:"$'\n\n'$(git log --pretty='- %s' "$LAST_TAG..HEAD")
else
  NOTES="Initial release."
fi

SUBJECT="build: release $VERSION — bump to $VERSION, embed signed release APK"
[ -n "$TICKET" ] && SUBJECT="$SUBJECT ($TICKET)"

git add -A || die "git add failed"
git commit -q -m "$SUBJECT" -m "$NOTES" || die "commit failed"
git tag -a "v$VERSION" -m "Release $VERSION" -m "$NOTES" || die "tag failed"

echo
echo "released $VERSION"
git --no-pager log --oneline -1
git --no-pager tag -n1 "v$VERSION"
