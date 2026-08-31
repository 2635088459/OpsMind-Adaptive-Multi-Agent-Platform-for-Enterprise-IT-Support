#!/usr/bin/env python3
"""Validate the OpsMind Telemetry Governance Baseline (SPEC-OP-003).

Checks infrastructure/observability/governance/telemetry-governance.yaml against
its schema and against the Collector config it drives:

  1. structural shape (version SemVer; required sections and keys present)
  2. deny_fields: non-empty, each pattern compiles, baseline concepts still covered
  3. Collector sync: the canonical deny regex derived from deny_fields appears
     verbatim in collector/base/config.yaml, once per (signal x context) = 6 times
  4. retention_classes: shape + duration format; bare class names used in governed
     artifact `retention:` metadata must exist here
  5. cardinality_budgets / signal_owners / schema_review shape
  6. exceptions: id format, all fields, dates parse, not expired, <= opened + 90d,
     deny_fields waivers carry policy-approval-governance sign-off

Exit 0 = OK (warnings allowed), 1 = one or more errors.

Requires PyYAML (CI: `pip install pyyaml`; local: `uv run --with pyyaml`).
"""
from __future__ import annotations

import datetime as dt
import json
import re
import sys
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover
    yaml = None  # checked in main(); importing this module never exits

REPO = Path(__file__).resolve().parents[1]
OBS = REPO / "infrastructure" / "observability"
GOV = OBS / "governance" / "telemetry-governance.yaml"
SCHEMA = OBS / "schemas" / "telemetry-governance.schema.json"
COLLECTOR = OBS / "collector" / "base" / "config.yaml"

DURATION = re.compile(r"^\d+[hdwy]$")
SEMVER = re.compile(r"^\d+\.\d+\.\d+$")
TGX_ID = re.compile(r"^TGX-\d{3,}$")
ISO_DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
MAX_WAIVER_DAYS = 90

# Concepts that must remain covered by at least one deny pattern — a guard against
# someone quietly gutting the baseline.
BASELINE_CONCEPTS = ["authorization", "token", "password", "cookie", "prompt", "ssn",
                     "api", "secret", "otp"]

errors: list[str] = []
warnings: list[str] = []


def err(m: str) -> None:
    errors.append(m)


def warn(m: str) -> None:
    warnings.append(m)


def canonical_regex(deny_fields: list[dict]) -> str:
    return "(?i)(" + "|".join(d["pattern"] for d in deny_fields) + ")"


def load() -> dict | None:
    if not GOV.is_file():
        err(f"missing {GOV.relative_to(REPO)}")
        return None
    try:
        doc = yaml.safe_load(GOV.read_text(encoding="utf-8"))
    except yaml.YAMLError as e:
        err(f"telemetry-governance.yaml: invalid YAML ({e})")
        return None
    if not isinstance(doc, dict):
        err("telemetry-governance.yaml: top level is not a mapping")
        return None
    return doc


def check_schema_file() -> None:
    if not SCHEMA.is_file():
        err(f"missing {SCHEMA.relative_to(REPO)}")
        return
    try:
        json.loads(SCHEMA.read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        err(f"telemetry-governance.schema.json: invalid JSON ({e})")


def check_structure(doc: dict) -> None:
    required = ["version", "deny_fields", "allow_fields", "retention_classes",
               "signal_owners", "cardinality_budgets", "schema_review", "exceptions"]
    for k in required:
        if k not in doc:
            err(f"telemetry-governance.yaml: missing top-level key '{k}'")
    if isinstance(doc.get("version"), str) and not SEMVER.match(doc["version"]):
        err(f"version {doc['version']!r} is not SemVer")

    af = doc.get("allow_fields", {})
    for sig in ("resource", "span", "log", "metric_datapoint"):
        if sig not in af:
            err(f"allow_fields: missing signal '{sig}'")
        elif not all(k in af[sig] for k in ("required", "recommended")):
            err(f"allow_fields.{sig}: needs 'required' and 'recommended' lists")
    if isinstance(af.get("resource"), dict):
        for must in ("service.name", "service.version", "deployment.environment"):
            if must not in af["resource"].get("required", []):
                err(f"allow_fields.resource.required must include {must!r}")

    for name, rc in (doc.get("retention_classes") or {}).items():
        if not isinstance(rc, dict):
            err(f"retention_classes.{name}: not a mapping")
            continue
        for k in ("description", "local", "ci", "prod"):
            if k not in rc:
                err(f"retention_classes.{name}: missing '{k}'")
        for k in ("local", "ci", "prod"):
            if k in rc and not DURATION.match(str(rc[k])):
                err(f"retention_classes.{name}.{k}={rc[k]!r} not a duration (\\d+[hdwy])")

    cb = doc.get("cardinality_budgets", {})
    if not isinstance(cb.get("global", {}).get("max_series_total"), int):
        err("cardinality_budgets.global.max_series_total must be an integer")
    for ns in cb.get("namespaces", []):
        for k in ("namespace", "max_label_keys", "max_series", "forbidden_labels"):
            if k not in ns:
                err(f"cardinality_budgets namespace entry missing '{k}': {ns!r}")

    for so in doc.get("signal_owners", []):
        for k in ("family", "semantic_owner", "transport_owner"):
            if k not in so:
                err(f"signal_owners entry missing '{k}': {so!r}")

    for k in ("rule", "additive_change", "breaking_change", "forbidden", "gate"):
        if k not in doc.get("schema_review", {}):
            err(f"schema_review: missing '{k}'")


def check_deny(doc: dict) -> None:
    deny = doc.get("deny_fields") or []
    if not deny:
        err("deny_fields is empty")
        return
    joined = " ".join(d.get("pattern", "") for d in deny).lower()
    for d in deny:
        if "pattern" not in d or "reason" not in d:
            err(f"deny_fields entry needs 'pattern' and 'reason': {d!r}")
            continue
        try:
            re.compile(d["pattern"])
        except re.error as e:
            err(f"deny_fields pattern {d['pattern']!r} does not compile ({e})")
    for concept in BASELINE_CONCEPTS:
        if concept not in joined:
            err(f"deny_fields no longer covers baseline concept {concept!r}")


def check_collector_sync(doc: dict) -> None:
    deny = doc.get("deny_fields") or []
    if not deny or not COLLECTOR.is_file():
        if not COLLECTOR.is_file():
            err(f"missing {COLLECTOR.relative_to(REPO)}")
        return
    text = COLLECTOR.read_text(encoding="utf-8")
    canon = canonical_regex(deny)
    # config.yaml escapes the backslash before . as \\ for YAML double-quoted scalars.
    canon_yaml = canon.replace("\\.", "\\\\.")
    count = text.count(canon_yaml)
    if count == 0:
        err("collector/base/config.yaml does not contain the canonical deny regex "
            "derived from deny_fields — governance policy and Collector config have "
            f"diverged. Expected substring:\n    {canon_yaml}")
    elif count != 6:
        warn(f"collector/base/config.yaml has the deny regex {count} times; "
             "expected 6 (resource+span, resource+datapoint, resource+log)")
    if "transform/governance" not in text:
        err("collector/base/config.yaml: no 'transform/governance' processor")
    elif "transform/governance, batch" not in text.replace(" ", " "):
        # loose check that it is wired into pipelines before batch
        if text.count("transform/governance") < 4:
            warn("transform/governance may not be wired into all three pipelines")


def check_retention_usage(doc: dict) -> None:
    classes = set((doc.get("retention_classes") or {}).keys())
    metas: list[tuple[str, str]] = []
    for p in sorted((OBS / "rules").rglob("*.yml")):
        for line in p.read_text(encoding="utf-8").splitlines():
            s = line.strip()
            if s.startswith("# meta.retention:"):
                metas.append((str(p.relative_to(OBS)), s.split(":", 1)[1].strip()))
    for base in ("runbooks", "signals"):
        for p in sorted((OBS / base).glob("*.md")):
            if p.name in {"README.md", "TEMPLATE.md"}:
                continue
            for line in p.read_text(encoding="utf-8").splitlines():
                s = line.strip()
                if s.startswith("> retention:"):
                    metas.append((str(p.relative_to(OBS)), s.split(":", 1)[1].strip()))
    for rel, val in metas:
        token = val.split(";")[0].split()[0] if val else ""
        if token and token.isalpha() and token not in classes:
            warn(f"{rel}: retention {val!r} looks like a class name but "
                 f"{token!r} is not in retention_classes {sorted(classes)}")


def check_exceptions(doc: dict) -> None:
    today = dt.date.today()
    for ex in doc.get("exceptions") or []:
        fields = ["id", "rule", "scope", "reason", "owner", "approved_by",
                  "opened", "expires", "ticket"]
        missing = [f for f in fields if f not in ex]
        label = ex.get("id", "<no id>")
        if missing:
            err(f"exception {label}: missing {missing}")
            continue
        if not TGX_ID.match(ex["id"]):
            err(f"exception {ex['id']}: id must match TGX-\\d{{3,}}")
        for k in ("opened", "expires"):
            if not ISO_DATE.match(str(ex[k])):
                err(f"exception {ex['id']}: {k}={ex[k]!r} not YYYY-MM-DD")
        try:
            opened = dt.date.fromisoformat(str(ex["opened"]))
            expires = dt.date.fromisoformat(str(ex["expires"]))
        except ValueError:
            continue
        if expires < today:
            err(f"exception {ex['id']}: EXPIRED on {expires} — renew or remove")
        if (expires - opened).days > MAX_WAIVER_DAYS:
            err(f"exception {ex['id']}: window {(expires - opened).days}d exceeds "
                f"{MAX_WAIVER_DAYS}d (opened {opened} -> expires {expires})")
        if str(ex["rule"]).startswith("deny_fields") and \
                "policy-approval-governance" not in str(ex["approved_by"]):
            err(f"exception {ex['id']}: deny_fields waiver needs domain-06 "
                "(policy-approval-governance) in approved_by")


def main() -> int:
    if yaml is None:
        print("ERROR validate-telemetry-governance: PyYAML not installed "
              "(pip install pyyaml / uv run --with pyyaml)")
        return 1
    check_schema_file()
    doc = load()
    if doc is not None:
        check_structure(doc)
        check_deny(doc)
        check_collector_sync(doc)
        check_retention_usage(doc)
        check_exceptions(doc)

    for w in warnings:
        print(f"WARN  {w}")
    for e in errors:
        print(f"ERROR {e}")
    print()
    print(f"validate-telemetry-governance: {len(errors)} error(s), {len(warnings)} warning(s)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
