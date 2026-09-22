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
cd "$(dirname "$0")" || exit 1

VERSION="${1:-}"
TICKET="${2:-}"
NOTES_FILE=""
NOTES_GIVEN=0
if [ "${2:-}" = "--notes" ]; then TICKET=""; NOTES_GIVEN=1; NOTES_FILE="${3:-}"; fi
if [ "${3:-}" = "--notes" ]; then NOTES_GIVEN=1; NOTES_FILE="${4:-}"; fi

die() { echo "release: $*" >&2; exit 1; }
step() { echo; echo "=================== $* ==================="; }

# Pure string comparison, no other side effects — kept as a standalone function so
# it can be sourced out of this file and unit-tested without running a release.
# Returns true (0) when $1 is a version strictly greater than $2.
version_gt() {
  [ "$1" != "$2" ] && [ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -1)" = "$1" ]
}

[ -n "$VERSION" ] || die "usage: ./release.sh <version> [T-nnn] [--notes FILE]"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "version must be MAJOR.MINOR.PATCH, got '$VERSION'"
if [ -n "$TICKET" ] && [[ ! "$TICKET" =~ ^T-[0-9]+$ ]]; then
  die "ticket must look like T-147, got '$TICKET'"
fi
# NOTES_GIVEN catches `--notes` with the filename left off (a typo, or the file
# argument forgotten) — that used to fall through silently to generated notes.
if [ "$NOTES_GIVEN" = 1 ] && [ -z "$NOTES_FILE" ]; then die "--notes requires a filename"; fi
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
version_gt "$VERSION" "$CURRENT_VERSION" ||
  die "version must increase: $VERSION is not newer than the current $CURRENT_VERSION"
NEXT_CODE=$((CURRENT_CODE + 1))

# The protocol version and the version number have to agree before anything is written (T-243):
# a protocol bump turns away every installed client, which is a MAJOR release by definition. The
# rules live in release-guard.py so they can be unit-tested; here we only read the four numbers.
# A previous tag older than protocol.py yields an empty string, which the guard counts as 0.
PROTOCOL_FILE=server/src/shoppinglist_server/protocol.py
read_protocol() { sed -n 's/^PROTOCOL_VERSION *= *\([0-9]*\).*/\1/p' | head -1; }
NEW_MAJOR="${VERSION%%.*}"
TREE_PROTOCOL=$(read_protocol < "$PROTOCOL_FILE")
[ -n "$TREE_PROTOCOL" ] || die "could not read PROTOCOL_VERSION from $PROTOCOL_FILE"
LAST_TAG=$(git describe --tags --abbrev=0 2>/dev/null)
PREV_PROTOCOL=""
PREV_MAJOR=""
if [[ "$LAST_TAG" =~ ^v([0-9]+)\.[0-9]+\.[0-9]+$ ]]; then
  PREV_MAJOR="${BASH_REMATCH[1]}"
  PREV_PROTOCOL=$(git show "$LAST_TAG:$PROTOCOL_FILE" 2>/dev/null | read_protocol)
fi
GUARD_REFUSAL=$(python3 release-guard.py "$TREE_PROTOCOL" "$NEW_MAJOR" "$PREV_PROTOCOL" "$PREV_MAJOR") ||
  die "${GUARD_REFUSAL:-release-guard.py failed}"

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
CERT_WILL_BE_COMPARED=0
PREVIOUS_CODE=""
if [ -f "$EMBEDDED_APK" ]; then
  # versionCode monotonicity is checked against the APK that actually shipped, not just the
  # number in build.gradle.kts: a merge or hand-edit can lower the constant in the source file
  # without anyone noticing, and Android refuses an update whose versionCode does not increase.
  PREVIOUS_BADGING=$("$AAPT2_BIN" dump badging "$EMBEDDED_APK" 2>/dev/null) ||
    die "aapt2 could not read the previously embedded APK ($EMBEDDED_APK) to check versionCode monotonicity"
  PREVIOUS_CODE=$(echo "$PREVIOUS_BADGING" | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p" | head -1)
  [ -n "$PREVIOUS_CODE" ] ||
    die "could not read versionCode from the previously embedded APK ($EMBEDDED_APK)"

  if [ "${RELEASE_FIRST_RELEASE:-0}" = 1 ]; then
    echo "  RELEASE_FIRST_RELEASE=1 set — skipping the previous-signing-key comparison even though $EMBEDDED_APK exists"
  else
    # Fatal, not skipped: a build-tools upgrade that rewords apksigner's output, or a truncated
    # APK from an interrupted run, must not let a differently-signed APK ship silently — after
    # which no installed user could update, and the tag could not be re-cut (T-280).
    PREVIOUS_CERT=$("$APKSIGNER_BIN" verify --print-certs "$EMBEDDED_APK" 2>/dev/null |
      sed -n 's/.*certificate SHA-256 digest: //p' | head -1)
    [ -n "$PREVIOUS_CERT" ] ||
      die "could not read the signing certificate of the previously embedded APK ($EMBEDDED_APK).
  Refusing to proceed rather than ship a possibly re-signed APK with no comparison. If this really
  is the first real release and $EMBEDDED_APK is a placeholder, rerun with RELEASE_FIRST_RELEASE=1
  to skip this check."
    CERT_WILL_BE_COMPARED=1
  fi
fi
[ -z "$PREVIOUS_CODE" ] || [ "$NEXT_CODE" -gt "$PREVIOUS_CODE" ] ||
  die "versionCode $NEXT_CODE would not be greater than $PREVIOUS_CODE, the versionCode of the
  previously shipped APK ($EMBEDDED_APK) — Android refuses to install an update whose versionCode
  does not increase. build.gradle.kts says the current versionCode is $CURRENT_CODE; check it
  against what was actually shipped."

echo "  version     $CURRENT_VERSION -> $VERSION"
echo "  versionCode $CURRENT_CODE -> $NEXT_CODE"
echo "  protocol    ${PREV_PROTOCOL:-0} -> $TREE_PROTOCOL"
echo "  ticket      ${TICKET:-(none given)}"
echo "  aapt2       $AAPT2_BIN"
echo "  apksigner   $APKSIGNER_BIN"

# ---------------------------------------------------------------------------
# From here on the tree is written to. A failure anywhere below used to leave a
# half-released tree behind — five bumped version files, a rebuilt web bundle,
# possibly a new embedded APK — with no trap and no recovery hint, so the natural
# next step was to hit "working tree is dirty" and commit the bumps (the incident
# AGENTS.md records as reverted in 52a6223). Every path touched below is a path
# already committed at HEAD (the precondition above required a clean tree), so on
# any failure it is restored from HEAD rather than left dirty: `git checkout` and
# `git clean` undo edits and stray build output respectively, chosen over "print
# a recovery command" because the whole point of the earlier incident was that the
# recovery command was not run.
# ---------------------------------------------------------------------------
START_HEAD=$(git rev-parse HEAD)
RELEASE_TOUCHED_PATHS=(
  server/pyproject.toml
  android/app/build.gradle.kts
  web/package.json
  web/package-lock.json
  server/src/shoppinglist_server/web_dist
  "$EMBEDDED_APK"
)
release_cleanup_on_failure() {
  local status=$?
  if [ "$status" -ne 0 ] && [ "$(git rev-parse HEAD)" = "$START_HEAD" ]; then
    echo
    echo "release: failed — restoring the tree (no commit was made)" >&2
    git reset -q -- "${RELEASE_TOUCHED_PATHS[@]}" 2>/dev/null
    git checkout -q -- "${RELEASE_TOUCHED_PATHS[@]}" 2>/dev/null
    git clean -fdq -- server/src/shoppinglist_server/web_dist 2>/dev/null
    if [ -n "$(git status --porcelain)" ]; then
      echo "release: could not fully restore the tree — check 'git status' and 'git diff' by hand" >&2
    else
      echo "release: tree restored to $START_HEAD; nothing was committed" >&2
    fi
  fi
}
trap release_cleanup_on_failure EXIT

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
# npm ci, not npm install: it installs exactly the lockfile and never rewrites it
# (T-201). Without it here, the bundle this release ships could be built against
# whatever happens to already be in web/node_modules.
( cd web && npm ci && npm run build ) || die "web build failed"

step "verifying everything"
# Before assembleRelease, not after: this is the slow, fail-fast gate, and there
# is no point signing an APK for a tree whose tests are red.
./verify-all.sh || die "verification failed"

step "building the signed release APK"
# Same gradle invocation family as verify-all's, so the daemon it just warmed is
# reused rather than a second one being started next to it — this machine is
# memory-tight. The daemon is stopped immediately afterwards.
#
# --offline first, like the gate: assembleRelease resolves R8 and the shrinker
# for the first time (verify-all.sh only exercises the debug variant), so a cold
# Gradle cache fails it here even when the debug build above succeeded. Retry
# online rather than fail the whole release over a cache that just needs warming,
# mirroring verify-all.sh's android_check.
APK_LOG=$(mktemp)
if ! ( cd android && ./gradlew :app:assembleRelease --offline ) 2>&1 | tee "$APK_LOG"; then
  if grep -qiE "offline mode|No cached version|available for offline" "$APK_LOG"; then
    rm -f "$APK_LOG"
    echo
    echo "=== assembleRelease: Gradle cache is cold; rerunning online ==="
    ( cd android && ./gradlew :app:assembleRelease )
    APK_STATUS=$?
  else
    rm -f "$APK_LOG"
    APK_STATUS=1
  fi
else
  rm -f "$APK_LOG"
  APK_STATUS=0
fi
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
if [ "$CERT_WILL_BE_COMPARED" = 1 ]; then
  if [ "$NEW_CERT" != "$PREVIOUS_CERT" ]; then
    # Android refuses to update an installed app across a change of signing key, so
    # this would strand every existing user on the version they already have.
    die "signing key CHANGED from the previous release
  was $PREVIOUS_CERT
  now $NEW_CERT"
  fi
  # Only printed once a real comparison happened — this used to print unconditionally,
  # including when PREVIOUS_CERT was empty because the comparison had been skipped (T-280).
  echo "  signed, same key as the previous release"
else
  echo "  signed (no previous release to compare the signing key against)"
fi

cp "$BUILT_APK" "$EMBEDDED_APK" || die "could not embed the APK"
echo "  embedded $(du -h "$EMBEDDED_APK" | cut -f1) at $EMBEDDED_APK"

# ---------------------------------------------------------------------------
step "building the wheel"
./build-wheel.sh || die "wheel build failed"

step "smoke-testing the wheel"
./smoke-wheel.sh "" "$VERSION" || die "wheel smoke test failed"

# ---------------------------------------------------------------------------
step "committing and tagging"

if [ -n "$NOTES_FILE" ]; then
  NOTES=$(cat "$NOTES_FILE")
elif [ -n "$LAST_TAG" ]; then
  NOTES="Changes since $LAST_TAG:"$'\n\n'$(git log --pretty='- %s' "$LAST_TAG..HEAD")
else
  NOTES="Initial release."
fi

SUBJECT="build: release $VERSION — bump to $VERSION, embed signed release APK"
[ -n "$TICKET" ] && SUBJECT="$SUBJECT ($TICKET)"

# The explicit path list, not `git add -A`: only what this script itself touched
# should ever land in the release commit (the AGENTS.md incident was exactly a
# broad `add` sweeping in changes nobody meant to commit).
git add -- "${RELEASE_TOUCHED_PATHS[@]}" || die "git add failed"
git commit -q -m "$SUBJECT" -m "$NOTES" || die "commit failed"
git tag -a "v$VERSION" -m "Release $VERSION" -m "$NOTES" ||
  die "tag failed — the release commit $(git rev-parse --short HEAD) already exists on $BRANCH.
  Nothing is restored past this point (never rewriting history): fix the problem and tag it by
  hand with: git tag -a v$VERSION -m 'Release $VERSION'"

echo
echo "released $VERSION"
git --no-pager log --oneline -1
git --no-pager tag -n1 "v$VERSION"
