#!/usr/bin/env bash
# Runs every verification suite (server lint + pytest, web vitest+tsc+lint, the wheel,
# Android test+lint) in one command — separate invocations were easy to skip one of
# (T-56). Prints a pass/fail summary with one line per stage at the end either way.
#
# Two ways to run (T-360):
#   parallel  the server stages, the web stage and the Android stages run as three
#             concurrent chains, each stage writing its own log. On the first failure
#             the other chains are stopped and the failed stage's log is printed.
#   serial    one stage after another, output straight to the terminal, stopping at
#             the first failure: for a host without the cores or memory to run Gradle,
#             vitest and pytest workers at once (the original Raspberry Pi).
# The default is parallel on a host with at least 6 cores and 12 GB of memory, serial
# otherwise. `--serial` / `--parallel`, or VERIFY_ALL_MODE=serial|parallel, choose.
set -uo pipefail
cd "$(dirname "$0")" || exit 1

# Fallback for the Android stage when JAVA_HOME isn't already exported. The sdkman
# path is this project's original dev machine; anywhere else, either export
# JAVA_HOME yourself or rely on Gradle finding a JDK on PATH.
JAVA_HOME_DEFAULT="${HOME}/.sdkman/candidates/java/current"

# The six stages, in the order the summary lists them.
STAGES=(
  "server (isort + black + ruff + ty)"
  "server (pytest)"
  "web (vitest + tsc + lint)"
  "server (wheel build + smoke test)"
  "android (preflight)"
  "android (test + lint)"
)
declare -A STAGE_FN=(
  ["server (isort + black + ruff + ty)"]=server_lint
  ["server (pytest)"]=server_check
  ["web (vitest + tsc + lint)"]=web_check
  ["server (wheel build + smoke test)"]=wheel_check
  ["android (preflight)"]=android_preflight
  ["android (test + lint)"]=android_check
)
# The chains of the parallel run. A chain runs its stages in order and stops at its
# first failure. The wheel follows pytest because pip rewrites
# server/src/shoppinglist_server.egg-info, which the editable install puts on pytest's
# path; Gradle starts only once Android's preflight has passed.
CHAIN_SERVER=("server (isort + black + ruff + ty)" "server (pytest)" "server (wheel build + smoke test)")
CHAIN_WEB=("web (vitest + tsc + lint)")
CHAIN_ANDROID=("android (preflight)" "android (test + lint)")

declare -A STAGE_STATUS

print_summary() {
  echo
  echo "==================== verify-all summary ===================="
  local name
  for name in "${STAGES[@]}"; do
    # SKIP: not run, or stopped before it finished because another stage failed.
    printf "  %-4s  %s\n" "${STAGE_STATUS[$name]:-SKIP}" "$name"
  done
  echo "==============================================================="
}

cpu_count() {
  nproc 2>/dev/null || getconf _NPROCESSORS_ONLN 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 1
}

# Total memory in MB, 0 when it cannot be read.
mem_total_mb() {
  local bytes
  if [ -r /proc/meminfo ]; then
    awk '/^MemTotal:/ { print int($2 / 1024); exit }' /proc/meminfo
  elif bytes=$(sysctl -n hw.memsize 2>/dev/null); then
    echo $((bytes / 1024 / 1024))
  else
    echo 0
  fi
}

server_check() (
  cd server || exit 1
  if [ ! -x .venv/bin/python ]; then
    echo "server/.venv missing — run: cd server && python3 -m venv .venv && .venv/bin/pip install -e '.[dev]'" >&2
    return 1
  fi
  if ! .venv/bin/python -c "import xdist" 2>/dev/null; then
    echo "server/.venv missing pytest-xdist — run: cd server && .venv/bin/pip install -e '.[dev]'" >&2
    return 1
  fi
  # -n: the tests run in parallel worker processes (pytest-xdist). Each test has its own
  # tmp_path and database, and nothing binds a port, so the workers share nothing.
  # worksteal hands a free worker the rest of a busy one's queue, which keeps the long
  # fuzz tests from deciding when the run ends.
  .venv/bin/python -m pytest -q -n "$PYTEST_WORKERS" --dist worksteal
)

# Lint/format gate for the server (T-141). Check-only — this verifies, it does not
# rewrite your tree; run `.venv/bin/isort . && .venv/bin/black .` to fix layout.
# Order matters: isort first, then black, because black has the final say on layout
# and isort runs in black's profile precisely so it can never disagree.
server_lint() (
  cd server || exit 1
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
  cd web || exit 1
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
    # Not `npx vitest`/`npx tsc`: npx fetches from the registry when a binary is
    # missing locally, which can silently run a version other than the one the
    # lockfile pins. Both are devDependencies, so node_modules/.bin always has them
    # once `npm ci` has run.
    # $VITEST_ARGS is unquoted on purpose: none, or the worker flags.
    # shellcheck disable=SC2086
    ./node_modules/.bin/vitest run $VITEST_ARGS &&
    ./node_modules/.bin/tsc --noEmit -p tsconfig.app.json &&
    npm run lint
)

# What a fresh machine got wrong today, each surfacing minutes into Gradle as a
# cryptic error (T-227): a JRE with no javac ("toolchain does not provide
# JAVA_COMPILER"), a JVM whose file-name encoding is not UTF-8 (a Kotlin internal
# compiler error writing the class file of a test whose name has an em dash), and
# no SDK location. Say each in one sentence before Gradle starts.
android_preflight() (
  cd android || exit 1
  if [ -z "${JAVA_HOME:-}" ] && [ -d "$JAVA_HOME_DEFAULT" ]; then
    export JAVA_HOME="$JAVA_HOME_DEFAULT"
  fi
  local javac=""
  if [ -n "${JAVA_HOME:-}" ]; then
    [ -x "$JAVA_HOME/bin/javac" ] && javac="$JAVA_HOME/bin/javac"
  else
    javac=$(command -v javac || true)
  fi
  if [ -z "$javac" ]; then
    echo "android: no javac — a JRE alone cannot build. Install a JDK (17 or newer), or point JAVA_HOME at one." >&2
    return 1
  fi
  local enc
  enc=$("${javac%javac}java" -XshowSettings:properties -version 2>&1 | sed -n 's/.*sun.jnu.encoding = //p')
  case "$enc" in
    UTF-8|UTF8|utf8) ;;
    *)
      echo "android: the JVM's file-name encoding is '${enc:-unknown}', not UTF-8, so Kotlin cannot write class files for tests named with non-ASCII characters. Export LC_ALL=C.UTF-8 or fix the host locale." >&2
      return 1
      ;;
  esac
  if [ ! -f local.properties ] && [ -z "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ]; then
    echo "android: no android/local.properties and no ANDROID_HOME — write 'sdk.dir=<Android SDK path>' to android/local.properties." >&2
    return 1
  fi
)

android_check() (
  cd android || exit 1
  # Only fall back to JAVA_HOME_DEFAULT if it actually exists; otherwise leave
  # JAVA_HOME unset and let Gradle locate a JDK itself.
  if [ -z "${JAVA_HOME:-}" ] && [ -d "$JAVA_HOME_DEFAULT" ]; then
    export JAVA_HOME="$JAVA_HOME_DEFAULT"
  fi
  # --offline keeps the gate deterministic on a warm cache, but on a fresh clone
  # it fails on the first missing dependency (T-227). Try offline, and when that
  # is the reason it failed, run once more online rather than fail the stage.
  #
  # testReleaseUnitTest, not just testDebugUnitTest (T-281): it is the only variant
  # that compiles the src/release source set, which is where the debug-only TLS
  # bypass is proven absent (see android/README.md). It does not minify or need
  # signing, so it belongs in the gate rather than release.sh, unlike assembleRelease.
  # :core:jvmTest runs the data layer's plain-JVM suite (android/core).
  # :shared:testDebugUnitTest runs the screens', view models' and stores' tests
  # (android/shared, T-352), and :shared:lintDebug lints that code, which
  # :app:lintDebug does not see. The :core:compileKotlinIos* and
  # :shared:compileKotlinIos* tasks compile the same code for the iPhone targets,
  # to klibs only (T-295): Kotlin/Native does that on Linux x86_64 and macOS, and
  # has no Linux/aarch64 host, so there the iOS check is left out.
  # :shared:verifyRoborazziDebug compares the screenshot tests' captures with the
  # committed images in android/shared/src/androidUnitTest/screenshots (T-325); it
  # runs them as part of :shared:testDebugUnitTest, so it costs no second test run.
  # --parallel lets :core, :shared and :app run their tasks at the same time (T-360);
  # org.gradle.workers.max, where a host sets it, still caps how many.
  local ios=""
  case "$(uname -s)/$(uname -m)" in
    Linux/x86_64 | Darwin/*) ios=":core:compileKotlinIosArm64 :core:compileKotlinIosSimulatorArm64 :shared:compileKotlinIosArm64 :shared:compileKotlinIosSimulatorArm64" ;;
  esac
  local log
  log=$(mktemp)
  # $ios is unquoted on purpose: none, or four task names.
  # shellcheck disable=SC2086
  if ./gradlew :core:jvmTest $ios :shared:testDebugUnitTest :shared:verifyRoborazziDebug :shared:lintDebug :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug --offline --parallel 2>&1 | tee "$log"; then
    rm -f "$log"
    return 0
  fi
  if grep -qiE "offline mode|No cached version|available for offline|could not resolve plugin artifact" "$log"; then
    rm -f "$log"
    echo
    echo "=== android: Gradle cache is cold; rerunning online ==="
    # shellcheck disable=SC2086
    ./gradlew :core:jvmTest $ios :shared:testDebugUnitTest :shared:verifyRoborazziDebug :shared:lintDebug :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug --parallel
  else
    rm -f "$log"
    return 1
  fi
)

# The gate never used to build or smoke-test the wheel, so a packaging regression
# (T-281) — a broken package-data glob, a schema.sql that stopped being included —
# surfaced only inside release.sh, after the tree had already been bumped. Both
# scripts cd to what they need themselves and are run from the repo root, same as
# release.sh runs them; they write only into server/dist and server/build, which
# are gitignored, and touch nothing else.
wheel_check() {
  ./build-wheel.sh && ./smoke-wheel.sh
}

# `./verify-all.sh --preflight` runs only the Android environment checks — what
# bootstrap.sh ends with, and a quick answer to "will the Android stage even start".
if [ "${1:-}" = "--preflight" ]; then
  android_preflight && echo "android preflight OK"
  exit $?
fi

MODE="${VERIFY_ALL_MODE:-auto}"
case "${1:-}" in
  --serial) MODE=serial ;;
  --parallel) MODE=parallel ;;
  "") ;;
  *)
    echo "usage: ./verify-all.sh [--serial | --parallel | --preflight]" >&2
    exit 2
    ;;
esac
CPUS=$(cpu_count)
MEM_MB=$(mem_total_mb)
case "$MODE" in
  serial | parallel) MODE_REASON="chosen" ;;
  auto)
    if [ "$CPUS" -ge 6 ] && [ "$MEM_MB" -ge 12000 ]; then MODE=parallel; else MODE=serial; fi
    MODE_REASON="$CPUS cores, $((MEM_MB / 1024)) GB"
    ;;
  *)
    echo "VERIFY_ALL_MODE must be serial, parallel or auto, not '$MODE'" >&2
    exit 2
    ;;
esac

# Test workers. Alone, pytest takes one per core. Next to Gradle, pytest and vitest
# get half the cores each: more only added contention, measured on 10 cores (T-360).
# In the serial mode vitest runs its files one at a time, as web/vite.config.ts says,
# because on a constrained host parallel jsdom files missed their timeouts.
# PYTEST_WORKERS overrides the pytest count (`0` runs the tests in this process).
HALF=$(((CPUS + 1) / 2))
[ "$HALF" -ge 2 ] || HALF=2
if [ "$MODE" = parallel ]; then
  PYTEST_WORKERS="${PYTEST_WORKERS:-$HALF}"
  VITEST_ARGS="--fileParallelism --maxWorkers=$HALF"
else
  PYTEST_WORKERS="${PYTEST_WORKERS:-auto}"
  VITEST_ARGS=""
fi

echo "verify-all: $MODE ($MODE_REASON)"

if [ "$MODE" = serial ]; then
  for name in "${STAGES[@]}"; do
    echo
    echo "=== $name ==="
    if "${STAGE_FN[$name]}"; then
      STAGE_STATUS["$name"]="PASS"
    else
      STAGE_STATUS["$name"]="FAIL"
      print_summary
      exit 1
    fi
  done
  print_summary
  echo "All checks passed."
  exit 0
fi

# ---------------------------------------------------------------------------
# Parallel run. Each chain is a background job in its own process group (set -m),
# so stopping a chain stops everything it started: pytest's and vitest's workers,
# and the Gradle client, whose daemon then cancels the build. A chain records each
# stage's result in LOG_DIR/<stage>.status and prints a line as each stage ends;
# the summary below reads those files and lists the stages in their fixed order.
# ---------------------------------------------------------------------------
LOG_DIR=$(mktemp -d "${TMPDIR:-/tmp}/verify-all.XXXXXX") || exit 1
slug() { echo "$1" | tr 'A-Z' 'a-z' | sed 's/[^a-z0-9]\{1,\}/-/g; s/^-//; s/-$//'; }

run_chain() {
  local name file start status
  for name in "$@"; do
    file="$LOG_DIR/$(slug "$name")"
    start=$SECONDS
    if "${STAGE_FN[$name]}" >"$file.log" 2>&1 </dev/null; then status=PASS; else status=FAIL; fi
    echo "$status" >"$file.status"
    printf "  %-4s  %s  (%ss)\n" "$status" "$name" $((SECONDS - start)) >&3
    [ "$status" = PASS ] || return 1
  done
}

CHAIN_PIDS=()
stop_chains() {
  local pid
  for pid in "${CHAIN_PIDS[@]}"; do
    kill -TERM -- "-$pid" 2>/dev/null
  done
  wait 2>/dev/null
}
trap 'echo; echo "verify-all: interrupted, stopping every stage" >&2; stop_chains; exit 130' INT TERM

echo "logs: $LOG_DIR"
echo
# Android is the longest chain, so the other two run at a lower priority: they still
# take every core Gradle leaves idle, but no longer slow Gradle down (the daemon that
# does the building is not a child of this script and keeps its own priority).
low_priority() { renice -n 10 -p "$BASHPID" >/dev/null 2>&1 || true; }
exec 3>&1
set -m
{ low_priority; run_chain "${CHAIN_SERVER[@]}"; } &
CHAIN_PIDS+=($!)
{ low_priority; run_chain "${CHAIN_WEB[@]}"; } &
CHAIN_PIDS+=($!)
run_chain "${CHAIN_ANDROID[@]}" &
CHAIN_PIDS+=($!)
set +m

FAILED=0
for _ in "${CHAIN_PIDS[@]}"; do
  if ! wait -n; then
    FAILED=1
    break
  fi
done
if [ "$FAILED" = 1 ]; then
  echo
  echo "verify-all: a stage failed, stopping the others"
  stop_chains
fi

for name in "${STAGES[@]}"; do
  file="$LOG_DIR/$(slug "$name")"
  [ -f "$file.status" ] && STAGE_STATUS["$name"]=$(cat "$file.status")
  if [ "${STAGE_STATUS[$name]:-}" = FAIL ]; then
    echo
    echo "=== $name: FAILED — its log, also at $file.log ==="
    cat "$file.log"
    echo "=== end of the $name log ==="
  fi
done

print_summary
if [ "$FAILED" = 1 ]; then
  echo "Logs of every stage: $LOG_DIR"
  exit 1
fi
rm -rf "$LOG_DIR"
echo "All checks passed."
