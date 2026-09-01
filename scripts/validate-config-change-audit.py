#!/usr/bin/env python3
"""Validate the OpsMind Observability Platform's config-change audit trail (SPEC-OP-032).

This domain's "ConfigurationRelease" LLD asset (11-security) requires every
config artifact to carry an owner, version/spec, access policy, retention,
runbook, rollback, and audit reference. `validate-observability-layout.py`'s
`check_governed_artifacts()` already enforces this for the artifacts it scans
(dashboards/*.json, rules/**/*.yml, runbooks/*.md, signals/*.md) — but the
actual deployable component CONFIG files themselves (collector/base/config.yaml,
prometheus/base/prometheus.yml, loki/base/loki.yml, tempo/base/tempo.yml,
alertmanager/base/alertmanager.yml, and every overlay/override file under them)
were never covered by that check, despite every one of them already following
the SAME "# owner: / # spec: / # rollback:" header-comment convention in
practice since SPEC-OP-002/ADR-0006 (confirmed empirically: every non-empty
file in scope already carries it — this validator makes that fact ENFORCED
going forward, not merely a habit a future spec could silently break).

This is the real, concrete "audit reference" this spec's own acceptance
criteria calls for: in a GitOps-only domain with no config-change database,
`git log`/`git blame` on a header-commented file IS the audit trail — this
validator's job is to make sure that trail can never go dark for a real
component config file.

Checks:
  1. Every non-empty *.yml/*.yaml/*.env file under a component's base/ or
     overlays/{local,ci,production}/ directory carries "# owner:", "# spec:",
     and "# rollback:" header lines within its first 10 lines.
  2. The "# spec:" line names at least one real SPEC-OP-0xx id (a sanity
     check against a header that's present but content-free, e.g. a copy-paste
     placeholder).
  3. The "# rollback:" line names a real rollback mechanism: either
     "git revert" or "recreate <service>" (this repo's two real, established
     rollback idioms — see docs/adr/0003-gitops-versioned-config-with-overlays.md).

Exit status: 0 = OK (warnings allowed), 1 = one or more errors.
Pure standard library. Python 3.9+.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
OBS = REPO / "infrastructure" / "observability"

COMPONENTS = ["collector", "prometheus", "loki", "tempo", "grafana", "alertmanager"]
OVERLAYS = ["local", "ci", "production"]
SCAN_SUFFIXES = {".yml", ".yaml", ".env"}

SPEC_ID = re.compile(r"SPEC-OP-\d{3}")
ROLLBACK_MECHANISM = re.compile(r"git revert|recreate\s+\S+", re.I)

errors: list[str] = []
warnings: list[str] = []


def err(msg: str) -> None:
    errors.append(msg)


def warn(msg: str) -> None:
    warnings.append(msg)


def _config_files() -> list[Path]:
    out: list[Path] = []
    for comp in COMPONENTS:
        comp_dir = OBS / comp
        if not comp_dir.is_dir():
            continue
        for sub in ["base", *[f"overlays/{o}" for o in OVERLAYS]]:
            d = comp_dir / sub
            if not d.is_dir():
                continue
            for path in sorted(d.rglob("*")):
                if path.is_file() and path.suffix.lower() in SCAN_SUFFIXES:
                    out.append(path)
    return out


def check_headers() -> None:
    for path in _config_files():
        rel = str(path.relative_to(REPO))
        text = path.read_text(encoding="utf-8", errors="replace")
        if not text.strip():
            continue  # an intentionally-empty placeholder (e.g. an unused overlay stub)
        head_lines = text.splitlines()[:10]
        head = "\n".join(head_lines)
        if "# owner:" not in head:
            err(f"{rel}: missing '# owner:' header within the first 10 lines")
        if "# spec:" not in head:
            err(f"{rel}: missing '# spec:' header within the first 10 lines")
        else:
            spec_line = next((l for l in head_lines if l.strip().startswith("# spec:")), "")
            if not SPEC_ID.search(spec_line):
                err(f"{rel}: '# spec:' header present but names no real SPEC-OP-0xx id: {spec_line.strip()!r}")
        if "# rollback:" not in head:
            err(f"{rel}: missing '# rollback:' header within the first 10 lines")
        else:
            rollback_line = next((l for l in head_lines if l.strip().startswith("# rollback:")), "")
            if not ROLLBACK_MECHANISM.search(rollback_line):
                err(f"{rel}: '# rollback:' header present but names neither "
                    f"'git revert' nor 'recreate <service>': {rollback_line.strip()!r}")


def main() -> int:
    check_headers()
    scanned = len(_config_files())
    for w in warnings:
        print(f"WARN  {w}")
    for e in errors:
        print(f"ERROR {e}")
    print(f"\nvalidate-config-change-audit: scanned {scanned} config file(s); "
          f"{len(errors)} error(s), {len(warnings)} warning(s)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
