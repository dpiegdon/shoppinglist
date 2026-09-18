#!/usr/bin/env bash
# Runs every verification suite (server lint + pytest, web vitest+tsc+lint, Android
# test+lint) in one command — three separate invocations were easy to skip one of
# (T-56). Fails fast: stops at the first failing stage, ordered fastest-first so a
# broken server/web change is caught before burning time on the much slower
# Android build. Prints a pass/fail summary table at the end either way.
set -uo pipefail
cd "$(dirname "$0")"

# Fallback for the Android stage when JAVA_HOME isn't already exported. The sdkman
# path is this project's original dev machine; anywhere else, either export
# JAVA_HOME yourself or rely on Gradle finding a JDK on PATH.
JAVA_HOME_DEFAULT="${HOME}/.sdkman/candidates/java/current"

STAGE_NAMES=()
declare -A STAGE_STATUS

print_summary() {
  echo
  echo "==================== verify-all summary ===================="
  for name in "${STAGE_NAMES[@]}"; do
    printf "  %-4s  %s\n" "${STAGE_STATUS[$name]}" "$name"
  done
  echo "==============================================================="
}

run_stage() {
  local name="$1"
  shift
  echo
  echo "=== $name ==="
  STAGE_NAMES+=("$name")
  if "$@"; then
    STAGE_STATUS["$name"]="PASS"
  else
    STAGE_STATUS["$name"]="FAIL"
    print_summary
    exit 1
  fi
}

server_check() (
  cd server
  if [ ! -x .venv/bin/python ]; then
    echo "server/.venv missing — run: cd server && python3 -m venv .venv && .venv/bin/pip install -e '.[dev]'" >&2
    return 1
  fi
  .venv/bin/python -m pytest -q
)

# Lint/format gate for the server (T-141). Check-only — this verifies, it does not
# rewrite your tree; run `.venv/bin/isort . && .venv/bin/black .` to fix layout.
# Order matters: isort first, then black, because black has the final say on layout
# and isort runs in black's profile precisely so it can never disagree.
server_lint() (
  cd server
  for tool in isort black ruff ty; do
    if [ ! -x ".venv/bin/$tool" ]; then
      echo "server/.venv missing $tool — run: cd server && .venv/bin/pip install -e '.[dev]'" >&2
      return 1
    fi
  done
  .venv/bin/isort --check-only . &&
    .venv/bin/black --check . &&
    .venv/bin/ruff check . &&
    .venv/bin/ty check .
)

web_check() (
  cd web
  # package-lock.json's version fields drift silently: nothing rewrites them
  # except a later `npm install`, which does it as a side effect and dirties
  # the tree (T-201). Catch the drift here instead of at that install.
  node -e '
    const pkg = require("./package.json").version;
    const lock = require("./package-lock.json").version;
    if (pkg !== lock) {
      console.error(`web: package.json is ${pkg} but package-lock.json is ${lock}`);
      process.exit(1);
    }
  ' &&
    npx vitest run &&
    npx tsc --noEmit -p tsconfig.app.json &&
    npm run lint
)

android_check() (
  cd android
  # Only fall back to JAVA_HOME_DEFAULT if it actually exists; otherwise leave
  # JAVA_HOME unset and let Gradle locate a JDK itself.
  if [ -z "${JAVA_HOME:-}" ] && [ -d "$JAVA_HOME_DEFAULT" ]; then
    export JAVA_HOME="$JAVA_HOME_DEFAULT"
  fi
  ./gradlew :app:testDebugUnitTest :app:lintDebug --offline
)

run_stage "server (isort + black + ruff + ty)" server_lint
run_stage "server (pytest)" server_check
run_stage "web (vitest + tsc + lint)" web_check
run_stage "android (test + lint)" android_check

print_summary
echo "All checks passed."
