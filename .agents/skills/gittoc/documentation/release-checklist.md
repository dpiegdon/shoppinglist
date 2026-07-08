# gittoc Release Checklist

This is a pragmatic checklist for all public `gittoc` releases.

## Run internal testsuite

The local dev pipeline runs all of the checks below (plus mypy type checking
and a vermin Python-floor check) in an isolated venv with pinned tool versions:

```bash
scripts/dev/check
```

Or run them individually (isort reads `.isort.cfg`, profile=black):

```bash
python3 -m isort --check scripts/gittoc_lib/ scripts/tests/
python3 -m black --check scripts/gittoc_lib/ scripts/tests/
python3 -m pyflakes scripts/gittoc_lib/ scripts/tests/
python3 -m mypy scripts/gittoc scripts/gittoc_lib/
python3 -m pytest scripts/tests/test_gittoc.py
python3 -m py_compile scripts/gittoc scripts/gittoc_lib/*.py scripts/tests/test_gittoc.py
```

## Check metadata versioning

In `scripts/gittoc_lib/__init__.py`, review `CURRENT_FORMAT_VERSION` and
`CURRENT_LAYOUT_VERSION`:

- **format_version** — bump if the JSON schema of issue files changed (new required
  fields, renamed fields, removed fields, changed semantics).
- **layout_version** — bump if the directory layout of the tracker branch changed
  (new or renamed subdirectories, new required files alongside issues).

If either version was bumped:
- Ensure a migration exists in `run_pending_migrations()` in `tracker.py` that
  upgrades old trackers to the new version.
- Migrations must be idempotent (safe to re-run).
- Test the upgrade path from the previous release tag.

If neither version changed, confirm no format/layout assumptions were silently broken.

## Verify correctness of documentation and embedding procedure

Verify documentation is up to date and accurate:
- `README.md` — commands, aliases, examples, version-gating note
- `SKILL.md` — version in frontmatter, command reference, workflow
- `AGENTS.md` — test/lint commands, style rules

Verify and test embedding into new repositories:
- Does the described procedure in `README.md` work correctly?
- Do agents with blank context pick the installed skill up?

Verify correctness of `setup` script.

## Check license

These must match:
- `LICENSE.txt` file
- license entry in `SKILL.md` header

## Version update

Update version strings in:
- `scripts/gittoc_lib/__init__.py` — `__version__`
- `SKILL.md` — frontmatter `version:`

## Tag and publish

```bash
git tag vX.Y.Z
git push origin main vX.Y.Z
```
