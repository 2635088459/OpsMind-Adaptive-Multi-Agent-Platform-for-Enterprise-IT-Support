#!/usr/bin/env python3
"""Validate the OpsMind Observability Platform repository layout.

Enforces the contract in
infrastructure/observability/docs/repository-layout.md (SPEC-OP-001):

  1. required top-level directories exist
  2. each component has base/ + overlays/{local,ci,production} and a README.md
  3. versions.env pins every component by IMAGE + TAG + DIGEST, no floating tags
  4. ADR set (0001..0006) + docs/adr/README.md present
  5. governed artifacts (dashboards/*.json, rules/**/*.yml, runbooks/*.md,
     signals/*.md) carry the required metadata header with a SemVer version
  6. "must never appear": no ':latest' image refs, no obviously write-capable
     Collector exporters / Alertmanager receivers in committed config

Exit status: 0 = OK (warnings allowed), 1 = one or more errors.

Pure standard library. Python 3.9+.
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
OBS = REPO / "infrastructure" / "observability"

COMPONENTS = ["collector", "prometheus", "loki", "tempo", "grafana", "alertmanager"]
OVERLAYS = ["local", "ci", "production"]
TOP_LEVEL_DIRS = [
    "docs",
    "docs/adr",
    "schemas",
    "signals",
    "rules/recording",
    "rules/alerting",
    "dashboards",
    "runbooks",
    *COMPONENTS,
]
ADRS = [
    "0001-otel-collector-sole-ingestion-boundary.md",
    "0002-prometheus-loki-tempo-backends.md",
    "0003-gitops-versioned-config-with-overlays.md",
    "0004-observability-never-mutates-business-state.md",
    "0005-thin-control-plane-api-only-when-gitops-insufficient.md",
    "0006-repository-layout-and-ownership-model.md",
    "0007-otlp-gateway-requires-tls-and-bearer-auth.md",
    "0008-sdk-level-redaction-contract.md",
    "0009-config-change-approval-and-audit.md",
    "0010-outage-recovery-rto-rpo-targets.md",
    "0011-cross-domain-traces-split-across-tenants.md",
]
VERSION_KEYS = {
    "collector": "OTEL_COLLECTOR",
    "prometheus": "PROMETHEUS",
    "loki": "LOKI",
    "tempo": "TEMPO",
    "grafana": "GRAFANA",
    "alertmanager": "ALERTMANAGER",
}
REQUIRED_META = [
    "owner",
    "version",
    "spec",
    "access_policy",
    "retention",
    "runbook",
    "rollback",
    "audit_ref",
]
SEMVER = re.compile(r"^\d+\.\d+\.\d+$")
FLOATING_TAG = re.compile(r"^(latest|stable|main|edge)$|^v?\d+$|^v?\d+\.\d+$", re.I)

errors: list[str] = []
warnings: list[str] = []


def err(msg: str) -> None:
    errors.append(msg)


def warn(msg: str) -> None:
    warnings.append(msg)


def check_tree() -> None:
    if not OBS.is_dir():
        err(f"missing {OBS.relative_to(OBS.parents[2])}")
        return
    for rel in TOP_LEVEL_DIRS:
        if not (OBS / rel).is_dir():
            err(f"missing directory: infrastructure/observability/{rel}")
    for comp in COMPONENTS:
        base = OBS / comp
        if not (base / "README.md").is_file():
            err(f"missing {comp}/README.md")
        if not (base / "base").is_dir():
            err(f"missing {comp}/base/")
        for ov in OVERLAYS:
            if not (base / "overlays" / ov).is_dir():
                err(f"missing {comp}/overlays/{ov}/")
    for name in ["README.md", *ADRS]:
        if not (OBS / "docs" / "adr" / name).is_file():
            err(f"missing docs/adr/{name}")
    if not (OBS / "schemas" / "artifact-metadata.schema.json").is_file():
        err("missing schemas/artifact-metadata.schema.json")


def check_versions() -> None:
    f = OBS / "versions.env"
    if not f.is_file():
        err("missing versions.env")
        return
    kv: dict[str, str] = {}
    for line in f.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, _, v = line.partition("=")
        kv[k.strip()] = v.strip()
    for comp, prefix in VERSION_KEYS.items():
        img, tag, digest = f"{prefix}_IMAGE", f"{prefix}_TAG", f"{prefix}_DIGEST"
        for key in (img, tag, digest):
            if key not in kv or not kv[key]:
                err(f"versions.env: missing {key}")
        if tag in kv:
            t = kv[tag].lstrip("v")
            if FLOATING_TAG.match(kv[tag]):
                err(f"versions.env: {tag}={kv[tag]!r} is a floating tag; pin an exact version")
            elif not re.match(r"^\d+\.\d+\.\d+", t):
                warn(f"versions.env: {tag}={kv[tag]!r} does not look like an exact version")
        if digest in kv:
            d = kv[digest]
            if d == "PENDING-SPEC-OP-002":
                warn(f"versions.env: {digest} not yet pinned (PENDING-SPEC-OP-002)")
            elif not re.match(r"^sha256:[0-9a-f]{64}$", d):
                err(f"versions.env: {digest}={d!r} must be 'sha256:<64 hex>' or 'PENDING-SPEC-OP-002'")


def _parse_md_meta(text: str) -> dict[str, str]:
    meta: dict[str, str] = {}
    for line in text.splitlines():
        s = line.strip()
        if s.startswith(">") and ":" in s:
            k, _, v = s[1:].strip().partition(":")
            meta[k.strip().lower()] = v.strip()
        elif s and not s.startswith("#") and not s.startswith(">"):
            if meta:
                break
    return meta


def _parse_yaml_meta(text: str) -> dict[str, str]:
    meta: dict[str, str] = {}
    for line in text.splitlines():
        s = line.strip()
        if s.startswith("# meta."):
            k, _, v = s[len("# meta."):].partition(":")
            meta[k.strip().lower()] = v.strip()
        elif s and not s.startswith("#"):
            break
    return meta


def _check_meta(rel: str, meta: dict[str, str]) -> None:
    for field in REQUIRED_META:
        if not meta.get(field):
            err(f"{rel}: metadata missing required field '{field}'")
    ver = meta.get("version", "")
    if ver and not SEMVER.match(ver):
        err(f"{rel}: metadata version {ver!r} is not SemVer (X.Y.Z)")
    rb = meta.get("runbook", "")
    if rb and rb != "self" and not rb.startswith(("http://", "https://")):
        if not (OBS / rb).exists() and not (OBS.parents[1] / rb).exists():
            warn(f"{rel}: runbook path {rb!r} does not exist yet")
    ar = meta.get("audit_ref", "")
    if ar and not ar.startswith(("http://", "https://")) and not (OBS.parents[1] / ar).exists():
        warn(f"{rel}: audit_ref path {ar!r} does not exist yet")


def check_governed_artifacts() -> None:
    skip = {"README.md", "README_CN.md", "README_EN.md", "TEMPLATE.md"}
    for path in sorted((OBS / "rules").rglob("*.yml")):
        rel = str(path.relative_to(OBS))
        _check_meta(rel, _parse_yaml_meta(path.read_text(encoding="utf-8")))
    for path in sorted((OBS / "runbooks").glob("*.md")):
        if path.name in skip:
            continue
        rel = str(path.relative_to(OBS))
        _check_meta(rel, _parse_md_meta(path.read_text(encoding="utf-8")))
    for path in sorted((OBS / "signals").glob("*.md")):
        if path.name in skip:
            continue
        rel = str(path.relative_to(OBS))
        _check_meta(rel, _parse_md_meta(path.read_text(encoding="utf-8")))
    for path in sorted((OBS / "dashboards").glob("*.json")):
        rel = str(path.relative_to(OBS))
        try:
            doc = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as e:
            err(f"{rel}: invalid JSON ({e})")
            continue
        meta = doc.get("__opsmind_meta")
        if not isinstance(meta, dict):
            err(f"{rel}: missing '__opsmind_meta' object")
            continue
        _check_meta(rel, {k: str(v) for k, v in meta.items()})


# Business-domain write surfaces that must never appear as a Collector exporter
# target or an Alertmanager/Grafana receiver URL. Infra *metric* receivers
# (postgresql, rabbitmq) are legitimate (SPEC-OP-029) and are intentionally not here.
FORBIDDEN_TARGETS = re.compile(
    r"/api/v1/(tickets|approvals|workflows|memories|evaluations|tools)\b"
    r"|\b(ticket-workflow-service|policy-approval-governance-service|"
    r"agent-runtime-service|memory-knowledge-service|"
    r"evaluation-improvement-service|user-access-authentication-service)"
    r"[:/][0-9a-z-]*/(api|internal)\b",
    re.I,
)


def check_no_business_writes() -> None:
    scan_dirs = [OBS / "collector", OBS / "alertmanager", OBS / "grafana"]
    for d in scan_dirs:
        for path in d.rglob("*"):
            if not path.is_file() or path.suffix.lower() not in {".yml", ".yaml", ".json"}:
                continue
            rel = str(path.relative_to(OBS))
            text = path.read_text(encoding="utf-8", errors="replace")
            if ":latest" in text:
                err(f"{rel}: contains ':latest' image reference")
            m = FORBIDDEN_TARGETS.search(text)
            if m:
                err(f"{rel}: possible business-write target {m.group(0)!r} "
                    f"(forbidden-business-writes F1/F4)")
    compose_files = list(OBS.rglob("docker-compose.yml")) + list(OBS.rglob("compose.yml"))
    stack = OBS.parents[0] / "docker-compose" / "observability-stack.yml"
    if stack.is_file():
        compose_files.append(stack)
    for path in compose_files:
        rel = str(path)
        text = path.read_text(encoding="utf-8", errors="replace")
        if ":latest" in text:
            err(f"{rel}: contains ':latest' image reference")
        m = FORBIDDEN_TARGETS.search(text)
        if m:
            err(f"{rel}: possible business-write target {m.group(0)!r}")


def check_no_committed_private_keys() -> None:
    """repository-layout.md §5: a private key must never be COMMITTED anywhere in
    this tree (SPEC-OP-008 / ADR-0007 added the first generated-secret case worth
    guarding). A dot-prefixed directory is this repo's established convention for
    "locally generated, gitignored, never committed" (.venv/, .pytest_cache/, and
    now collector/overlays/local/.tls/ — see .gitignore) — skip those rather than
    shelling out to git, which would misbehave against a test clone with no .git."""
    marker = "-----BEGIN " + "PRIVATE KEY-----"  # split so this file isn't itself a hit
    for path in OBS.rglob("*"):
        if not path.is_file() or path.is_symlink():
            continue
        rel_parts = path.relative_to(OBS).parts
        if any(part.startswith(".") for part in rel_parts[:-1]):
            continue
        try:
            text = path.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        if marker in text:
            err(f"{path.relative_to(OBS)}: appears to contain a private key outside "
                f"a dot-prefixed (gitignored) directory (repository-layout.md §5) — "
                f"generate it at runtime into a gitignored path instead")


def main() -> int:
    check_tree()
    check_versions()
    check_governed_artifacts()
    check_no_business_writes()
    check_no_committed_private_keys()

    for w in warnings:
        print(f"WARN  {w}")
    for e in errors:
        print(f"ERROR {e}")

    print()
    print(f"validate-observability-layout: {len(errors)} error(s), {len(warnings)} warning(s)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
