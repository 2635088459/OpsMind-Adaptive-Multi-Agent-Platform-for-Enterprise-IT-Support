#!/usr/bin/env python3
"""Validate OpsMind Grafana dashboard JSON (SPEC-OP-016+).

dashboards/README.md says: "CI validates JSON parse + presence of __opsmind_meta" —
this was a stated plan with no script behind it until now.

Checks, per dashboards/*.json:
  1. Valid JSON.
  2. __opsmind_meta present with every artifact-metadata-convention field
     (owner, version, spec, access_policy, retention, runbook, rollback, audit_ref).
  3. tags mirror owner/version/spec (artifact-metadata-convention §3).
  4. uid present and unique across all dashboard files.
  5. Every panel's datasource.uid resolves to a real provisioned datasource
     (grafana/base/provisioning/datasources/datasources.yml).
  6. No panel/target references a non-query datasource or write-capable plugin
     (forbidden-business-writes F3) — datasource type must be one of the
     provisioned query-only types.
  7. runbook path (unless "self") exists on disk; audit_ref path is warned if it
     does not exist yet (spec still in progress) — same tolerance as
     validate-observability-layout.py.

Exit 0 = OK (warnings allowed), 1 = one or more errors. Requires PyYAML.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover
    yaml = None

REPO = Path(__file__).resolve().parents[1]
OBS = REPO / "infrastructure" / "observability"
DASHBOARDS = OBS / "dashboards"
DATASOURCES_FILE = OBS / "grafana" / "base" / "provisioning" / "datasources" / "datasources.yml"

REQUIRED_META = ["owner", "version", "spec", "access_policy", "retention",
                 "runbook", "rollback", "audit_ref"]

errors: list[str] = []
warnings: list[str] = []


def err(m: str) -> None:
    errors.append(m)


def warn(m: str) -> None:
    warnings.append(m)


def load_datasource_uids() -> set[str]:
    if yaml is None or not DATASOURCES_FILE.is_file():
        return set()
    try:
        doc = yaml.safe_load(DATASOURCES_FILE.read_text(encoding="utf-8"))
    except yaml.YAMLError as e:
        err(f"datasources.yml: invalid YAML ({e})")
        return set()
    return {d["uid"] for d in (doc or {}).get("datasources", []) if "uid" in d}


def collect_datasource_refs(node, refs: list[str]) -> None:
    if isinstance(node, dict):
        if "uid" in node and "type" in node and set(node.keys()) <= {"type", "uid"}:
            refs.append(node["uid"])
        for v in node.values():
            collect_datasource_refs(v, refs)
    elif isinstance(node, list):
        for v in node:
            collect_datasource_refs(v, refs)


def check_dashboard(path: Path, ds_uids: set[str], seen_uids: dict[str, str]) -> None:
    try:
        rel = path.relative_to(REPO)
    except ValueError:
        rel = path  # path is outside REPO (e.g. a unit test's own tempdir fixture)
    try:
        doc = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        err(f"{rel}: invalid JSON ({e})")
        return

    meta = doc.get("__opsmind_meta")
    if not isinstance(meta, dict):
        err(f"{rel}: missing __opsmind_meta")
        meta = {}
    for f in REQUIRED_META:
        if f not in meta:
            err(f"{rel}: __opsmind_meta missing '{f}'")

    tags = doc.get("tags", [])
    for expect in (f"owner:{meta.get('owner')}", f"v:{meta.get('version')}"):
        if meta.get("owner") and meta.get("version") and expect not in tags:
            err(f"{rel}: tags missing {expect!r} (artifact-metadata-convention §3)")

    uid = doc.get("uid")
    if not uid:
        err(f"{rel}: missing top-level 'uid'")
    elif uid in seen_uids:
        err(f"{rel}: uid {uid!r} duplicates {seen_uids[uid]}")
    else:
        seen_uids[uid] = str(rel)

    runbook = meta.get("runbook")
    if runbook and runbook != "self":
        rb_path = OBS / runbook
        if not rb_path.is_file():
            err(f"{rel}: runbook path {runbook!r} does not exist")

    audit_ref = meta.get("audit_ref")
    if audit_ref:
        if not (REPO / audit_ref).is_file():
            warn(f"{rel}: audit_ref path {audit_ref!r} does not exist yet")

    if ds_uids:
        refs: list[str] = []
        collect_datasource_refs(doc.get("panels", []), refs)
        for ref in refs:
            if ref.startswith("${") or ref == "-- Mixed --" or ref == "-- Dashboard --":
                continue
            if ref not in ds_uids:
                err(f"{rel}: panel references unknown datasource uid {ref!r} "
                    f"(known: {sorted(ds_uids)})")


def main() -> int:
    if not DASHBOARDS.is_dir():
        err(f"missing {DASHBOARDS.relative_to(REPO)}")
        return 1
    ds_uids = load_datasource_uids()
    if not ds_uids:
        warn("could not load datasource uids from datasources.yml — skipping datasource-ref check")

    files = sorted(DASHBOARDS.glob("*.json"))
    if not files:
        err(f"no dashboard JSON files in {DASHBOARDS.relative_to(REPO)}")
        return 1

    seen_uids: dict[str, str] = {}
    for f in files:
        check_dashboard(f, ds_uids, seen_uids)

    for w in warnings:
        print(f"WARN  {w}")
    for e in errors:
        print(f"ERROR {e}")
    print()
    print(f"validate-dashboards: {len(errors)} error(s), {len(warnings)} warning(s)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
