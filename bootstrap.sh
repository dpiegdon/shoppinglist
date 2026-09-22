#!/usr/bin/env bash
# Sets up a fresh checkout so that ./verify-all.sh can run (T-229): the server
# venv, the web dependencies, the Android SDK location, and a check of what it
# cannot install for you (a JDK, a UTF-8 locale). Idempotent — every step is
# skipped when it is already done — and it never touches a keystore, a database
# or anything under android/keystore.
#
#   ./bootstrap.sh                # set up; print what is still missing
#   ./bootstrap.sh --android-sdk  # also download a minimal Android SDK to
#                                 # $ANDROID_HOME (default ~/Android/Sdk)
#
# It does not run the gate; that is verify-all.sh's job.
set -uo pipefail
cd "$(dirname "$0")" || exit 1

INSTALL_SDK=0
[ "${1:-}" = "--android-sdk" ] && INSTALL_SDK=1

step() { echo; echo "=== $* ==="; }
missing=0

# ---------------------------------------------------------------------------
step "server: virtualenv with the package installed editable"
if [ -x server/.venv/bin/python ]; then
  echo "server/.venv exists"
else
  python3 -m venv server/.venv || { echo "python3 -m venv failed — Python 3.11 or newer is required" >&2; exit 1; }
fi
( cd server && .venv/bin/pip install -q -e '.[dev]' ) || exit 1
echo "server/.venv ready ($(server/.venv/bin/python --version))"

# ---------------------------------------------------------------------------
step "web: node dependencies"
if ! command -v npm >/dev/null; then
  echo "npm not found — install Node 20 or newer to run the web stage" >&2
  missing=1
elif [ -d web/node_modules ]; then
  echo "web/node_modules exists"
else
  # npm ci, not npm install: it installs exactly the lockfile and never rewrites
  # it, and the gate fails on lockfile drift (T-201).
  ( cd web && npm ci ) || exit 1
fi

# ---------------------------------------------------------------------------
step "android: JDK"
if command -v javac >/dev/null || [ -x "${JAVA_HOME:-/nonexistent}/bin/javac" ]; then
  echo "javac found"
else
  echo "no javac — a JRE alone cannot build the app. Install a JDK (17 or newer)," >&2
  echo "e.g. 'sudo apt install openjdk-21-jdk', or point JAVA_HOME at one." >&2
  missing=1
fi

# ---------------------------------------------------------------------------
step "android: SDK location"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
if [ -f android/local.properties ]; then
  echo "android/local.properties exists: $(cat android/local.properties)"
elif [ -d "$SDK/platforms" ]; then
  echo "sdk.dir=$SDK" > android/local.properties
  echo "wrote android/local.properties -> $SDK"
elif [ "$INSTALL_SDK" = 1 ]; then
  # A minimal SDK: the command-line tools, then exactly what app/build.gradle.kts
  # compiles against. Gradle downloads anything else it needs on the first build.
  echo "installing a minimal Android SDK into $SDK"
  mkdir -p "$SDK/cmdline-tools"
  tmp=$(mktemp -d)
  CMDLINE_ZIP_URL=https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  # The sha1 Google's own package XML (repository2-3.xml, cmdline-tools;12.0, linux
  # archive) lists for this exact file — pinned here rather than fetched at install
  # time, so a compromised or truncated download is caught before anything is
  # unpacked (T-281). If this ever needs to move to a newer cmdline-tools build,
  # look the new sha1 up in that XML rather than trusting an unpinned download.
  CMDLINE_ZIP_SHA1=d313adb7aedccf6cf0cfca51ec180f0059f5f8f8
  curl -sSL -f -o "$tmp/cmdline.zip" "$CMDLINE_ZIP_URL" || {
    echo "download of $CMDLINE_ZIP_URL failed" >&2
    rm -rf "$tmp"
    exit 1
  }
  ACTUAL_SHA1=$(sha1sum "$tmp/cmdline.zip" | cut -d' ' -f1)
  if [ "$ACTUAL_SHA1" != "$CMDLINE_ZIP_SHA1" ]; then
    echo "checksum mismatch for $CMDLINE_ZIP_URL: expected $CMDLINE_ZIP_SHA1, got $ACTUAL_SHA1" >&2
    rm -rf "$tmp"
    exit 1
  fi
  unzip -q -o "$tmp/cmdline.zip" -d "$SDK/cmdline-tools" || {
    echo "could not unzip $tmp/cmdline.zip into $SDK/cmdline-tools" >&2
    rm -rf "$tmp"
    exit 1
  }
  rm -rf "$tmp"
  [ -d "$SDK/cmdline-tools/cmdline-tools" ] && mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
  yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null 2>&1 || true
  "$SDK/cmdline-tools/latest/bin/sdkmanager" "platform-tools" "platforms;android-36" "build-tools;36.0.0" || exit 1
  echo "sdk.dir=$SDK" > android/local.properties
  echo "wrote android/local.properties -> $SDK"
else
  echo "no Android SDK at $SDK and no android/local.properties." >&2
  echo "Either set ANDROID_HOME to an existing SDK, or rerun with --android-sdk to download one." >&2
  missing=1
fi

# ---------------------------------------------------------------------------
step "android: environment preflight"
if ./verify-all.sh --preflight; then
  :
else
  missing=1
fi

echo
if [ "$missing" = 0 ]; then
  echo "Ready. Run ./verify-all.sh — the first Android build downloads Gradle and its dependencies."
else
  echo "Not ready: fix what is listed above, then rerun ./bootstrap.sh." >&2
  exit 1
fi
