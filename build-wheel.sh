#!/usr/bin/env bash
# Builds the deployable wheel, correctly (T-105).
#
# Exists because "clean, then build" is one step that is trivial to skip and
# fails silently when you do: setuptools reuses server/build/ across builds and
# only ever ADDS to it, so a file deleted from src/ since the last build gets
# packaged again. Two releases shipped the previous release's web bundle that
# way before anyone noticed. Doing the clean here means the release can't be cut
# without it.
#
# Does NOT rebuild web_dist or the APK — those are committed inputs with their
# own (much slower) toolchains. Rebuild them first if this release changes them:
#   cd web && npm run build
#   cd android && ./gradlew :app:assembleRelease   # then cp to src/.../apk/
set -uo pipefail
cd "$(dirname "$0")/server"

PY=.venv/bin/python
if [ ! -x "$PY" ]; then
  echo "server/.venv missing — run: cd server && python3 -m venv .venv && .venv/bin/pip install -e '.[dev]'" >&2
  exit 1
fi

echo "=== cleaning stale build artifacts ==="
rm -rf build dist
echo "removed server/build and server/dist"

echo
echo "=== building wheel ==="
# pip wheel rather than `python -m build --wheel`: same result from the same
# backend, and it needs no extra build-time dependency in the venv.
"$PY" -m pip wheel . --no-deps -w dist -q || exit 1
WHEEL=$(ls dist/*.whl 2>/dev/null | head -1)
if [ -z "$WHEEL" ]; then
  echo "no wheel produced" >&2
  exit 1
fi
echo "built $WHEEL"

echo
echo "=== verifying wheel contents ==="
"$PY" - "$WHEEL" <<'PY' || exit 1
import hashlib, re, sys, zipfile

wheel = sys.argv[1]
z = zipfile.ZipFile(wheel)
names = z.namelist()
problems = []

version = next(
    l.split(": ", 1)[1]
    for n in names if n.endswith(".dist-info/METADATA")
    for l in z.read(n).decode().splitlines() if l.startswith("Version: ")
)
print(f"  version:  {version}")

# The staleness check: assets present must be exactly the ones index.html asks
# for. A leftover bundle from a previous build shows up here as an extra.
index = next((n for n in names if n.endswith("web_dist/index.html")), None)
if index is None:
    problems.append("no web_dist/index.html — was `npm run build` ever run?")
else:
    referenced = {r.split("/")[-1] for r in re.findall(r'/assets/([^"\']+)', z.read(index).decode())}
    present = {n.split("/")[-1] for n in names if "/web_dist/assets/" in n}
    print(f"  assets:   {len(present)} present, {len(referenced)} referenced by index.html")
    for stale in sorted(present - referenced):
        problems.append(f"stale asset not referenced by index.html: {stale}")
    for missing in sorted(referenced - present):
        problems.append(f"index.html references a missing asset: {missing}")

for required in ("schema.sql", "migrations.py", "templates/invite.html"):
    if not any(n.endswith(required) for n in names):
        problems.append(f"missing {required}")

apk = [n for n in names if n.endswith(".apk")]
if not apk:
    problems.append("no APK embedded — /shoppinglist.apk will 404")
else:
    print(f"  apk:      {len(z.read(apk[0]))} bytes, sha256 {hashlib.sha256(z.read(apk[0])).hexdigest()[:16]}")

if problems:
    print("\nFAILED:")
    for p in problems:
        print(f"  - {p}")
    sys.exit(1)
print("  contents OK")
PY

echo
echo "Wheel ready: server/$WHEEL"
echo "Smoke-test it in a clean venv before tagging."
