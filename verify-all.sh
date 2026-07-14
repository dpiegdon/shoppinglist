#!/usr/bin/env bash
# Runs all three verification suites (server pytest, web vitest+tsc+lint, Android
# test+lint) in one command — three separate invocations were easy to skip one of
# (T-56). Fails fast: stops at the first failing stage, ordered fastest-first so a
# broken server/web change is caught before burning time on the much slower
# Android build. Prints a pass/fail summary table at the end either way.
set -uo pipefail
cd "$(dirname "$0")"

JAVA_HOME_DEFAULT="/home/claude/.sdkman/candidates/java/current"

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

web_check() (
  cd web
  npx vitest run &&
    npx tsc --noEmit -p tsconfig.app.json &&
    npm run lint
)

android_check() (
  cd android
  JAVA_HOME="${JAVA_HOME:-$JAVA_HOME_DEFAULT}" ./gradlew :app:testDebugUnitTest :app:lintDebug --offline
)

run_stage "server (pytest)" server_check
run_stage "web (vitest + tsc + lint)" web_check
run_stage "android (test + lint)" android_check

print_summary
echo "All checks passed."
