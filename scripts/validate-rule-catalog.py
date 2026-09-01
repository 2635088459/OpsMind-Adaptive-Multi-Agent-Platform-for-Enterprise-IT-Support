#!/usr/bin/env python3
"""Validate the OpsMind recording/alert rule CATALOG (SPEC-OP-020 / SPEC-OP-024).

validate-observability-layout.py already checks the FILE-LEVEL `# meta.*` header on
every rules/*.yml file. Nothing has ever checked the content of an individual
alerting rule — this closes that gap:

  1. Every alerting rule sets labels.severity and labels.owner
     (rules/README.md's own stated convention, never enforced until now).
  2. Every alerting rule sets annotations.summary, .description, .runbook_url,
     and .dashboard.
  3. Every recording rule name follows Prometheus's own `level:metric:operation`
     colon convention (informational warning only — not every rule fits this
     cleanly, e.g. this domain's own `http:request:rate5m`).
  4. runbook_url resolves to a REAL file under runbooks/ — parses the
     `.../runbooks/<Name>.md` suffix out of the URL and checks it exists on disk.
     This is the actual cross-reference SPEC-OP-024's "runbook catalog" promises
     and nothing checked before.
  5. Every runbook file under runbooks/ (excluding README/TEMPLATE) is referenced
     by at least one alert's runbook_url OR by a dashboard's `__opsmind_meta.runbook`
     — an orphaned runbook nobody's alert points to is a catalog gap in the other
     direction.
  6. (SPEC-OP-024) Every runbook referenced by an alert's runbook_url actually
     HAS the runbooks/README.md-promised body sections (Impact, Detection,
     Triage, Mitigation, Resolution, Rollback, Escalation, Post-incident), each
     with real content — not missing, not still the literal TEMPLATE.md
     boilerplate copy-pasted and never filled in. #4 above only ever checked
     that the runbook_url FILE exists; nothing has checked what's actually
     inside it. A runbook linked from at least one `severity: critical`
     ("paging") alert fails the build outright if a section is missing/still a
     placeholder; a runbook linked only from `warning`-tier alerts gets a
     warning instead — enforcement is strict exactly where the concrete
     objective says it must be ("every PAGING alert"), advisory everywhere else.

Exit 0 = OK (warnings allowed), 1 = one or more errors. Requires PyYAML.
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover
    yaml = None

REPO = Path(__file__).resolve().parents[1]
OBS = REPO / "infrastructure" / "observability"
RULES_ALERTING = OBS / "rules" / "alerting"
RULES_RECORDING = OBS / "rules" / "recording"
LOKI_RULES = OBS / "loki" / "rules"  # SPEC-OP-013 — LogQL alerting rules, a
                                      # separate rule family from Prometheus's own.
RUNBOOKS = OBS / "runbooks"
DASHBOARDS = OBS / "dashboards"

RUNBOOK_URL_RE = re.compile(r"runbooks/([A-Za-z0-9_.-]+\.md)\b")
RECORDING_NAME_RE = re.compile(r"^[a-z][a-z0-9_]*(:[a-z][a-z0-9_]*)+$")

REQUIRED_ALERT_LABELS = ["severity", "owner"]
REQUIRED_ALERT_ANNOTATIONS = ["summary", "description", "runbook_url", "dashboard"]

# SPEC-OP-024 — runbooks/README.md's own promised body-section contract, and the
# exact TEMPLATE.md boilerplate for each (normalized: collapsed whitespace,
# stripped) — a real runbook whose section body still equals this verbatim was
# copy-pasted and never actually filled in.
REQUIRED_RUNBOOK_SECTIONS = [
    "Impact", "Detection", "Triage", "Mitigation",
    "Resolution", "Rollback", "Escalation", "Post-incident",
]
_TEMPLATE_PLACEHOLDER_RAW = {
    "Impact": "What breaks for users / the business when this fires. State whether the business "
              "request path is affected (usually: no — observability only).",
    "Detection": "- Firing expression: `<promql>` - Dashboard: `<link to dashboards/...>` "
                 "- Correlation entry point: `<Explore query: metrics ↔ logs ↔ traces>`",
    "Triage": "Ordered checks to classify the cause (saturation vs. dependency vs. config).",
    "Mitigation": "Fast actions to stop the bleeding. If a step touches a business domain, route it "
                  "through domain-06 approval + domain-05 tool gateway — never a direct write from here.",
    "Resolution": "Durable fix.",
    "Rollback": "Exact revert (matches the `rollback:` header).",
    "Escalation": "Who to page and when (owner → secondary → domain owner).",
    "Post-incident": "Link the traceability entry; note residual risk and any follow-up spec.",
}


def _normalize(text: str) -> str:
    return " ".join(text.split())


_TEMPLATE_PLACEHOLDER = {k: _normalize(v) for k, v in _TEMPLATE_PLACEHOLDER_RAW.items()}
MIN_SECTION_CHARS = 15

errors: list[str] = []
warnings: list[str] = []


def err(m: str) -> None:
    errors.append(m)


def warn(m: str) -> None:
    warnings.append(m)


def load_yaml(p: Path) -> dict | None:
    try:
        return yaml.safe_load(p.read_text(encoding="utf-8"))
    except yaml.YAMLError as e:
        err(f"{p.name}: invalid YAML ({e})")
        return None


def _rel(p: Path) -> Path:
    try:
        return p.relative_to(REPO)
    except ValueError:
        return p  # a unit test's own tempdir fixture, outside REPO


def check_alerting_file(
    p: Path, referenced_runbooks: set[str], runbook_severities: dict[str, set[str]]
) -> None:
    doc = load_yaml(p)
    if not isinstance(doc, dict):
        return
    for group in doc.get("groups") or []:
        for rule in group.get("rules") or []:
            if "alert" not in rule:
                continue  # a plain recording rule mixed into an alerting file
            name = rule["alert"]
            labels = rule.get("labels") or {}
            annotations = rule.get("annotations") or {}
            for f in REQUIRED_ALERT_LABELS:
                if f not in labels:
                    err(f"{_rel(p)}: alert {name!r} missing labels.{f}")
            for f in REQUIRED_ALERT_ANNOTATIONS:
                if f not in annotations:
                    err(f"{_rel(p)}: alert {name!r} missing annotations.{f}")
            runbook_url = annotations.get("runbook_url", "")
            m = RUNBOOK_URL_RE.search(runbook_url)
            if not m:
                if runbook_url:
                    err(f"{_rel(p)}: alert {name!r} runbook_url {runbook_url!r} "
                        f"does not point at runbooks/<Name>.md")
                continue
            rb_name = m.group(1)
            referenced_runbooks.add(rb_name)
            runbook_severities.setdefault(rb_name, set()).add(labels.get("severity", ""))
            if not (RUNBOOKS / rb_name).is_file():
                err(f"{_rel(p)}: alert {name!r} runbook_url references "
                    f"runbooks/{rb_name}, which does not exist")


def check_recording_file(p: Path) -> None:
    doc = load_yaml(p)
    if not isinstance(doc, dict):
        return
    for group in doc.get("groups") or []:
        for rule in group.get("rules") or []:
            if "record" not in rule:
                continue
            name = rule["record"]
            if not RECORDING_NAME_RE.match(name):
                warn(f"{_rel(p)}: recording rule {name!r} does not follow "
                     f"the level:metric:operation colon convention")


def _split_sections(body: str) -> dict[str, str]:
    """Split a runbook's Markdown body on `## Heading` lines into {heading: content}."""
    sections: dict[str, str] = {}
    current: str | None = None
    buf: list[str] = []
    for line in body.splitlines():
        m = re.match(r"^##\s+(.+?)\s*$", line)
        if m:
            if current is not None:
                sections[current] = "\n".join(buf)
            current = m.group(1)
            buf = []
        elif current is not None:
            buf.append(line)
    if current is not None:
        sections[current] = "\n".join(buf)
    return sections


def check_runbook_structure(runbook_severities: dict[str, set[str]]) -> None:
    """SPEC-OP-024 — the runbooks/README.md-promised body sections must actually be
    present and filled in for every runbook an alert's runbook_url points to. Strict
    (error) for any runbook backing at least one `severity: critical` ("paging")
    alert; advisory (warning) for one backing only `warning`-tier alerts — matching
    the concrete objective's own wording ("every PAGING alert")."""
    for rb_name, severities in sorted(runbook_severities.items()):
        path = RUNBOOKS / rb_name
        if not path.is_file():
            continue  # already reported by check_alerting_file
        report = err if "critical" in severities else warn
        sections = _split_sections(path.read_text(encoding="utf-8"))
        for heading in REQUIRED_RUNBOOK_SECTIONS:
            content = sections.get(heading)
            if content is None:
                report(f"runbooks/{rb_name}: missing required '## {heading}' section "
                       f"(referenced by severity={sorted(severities)} alert(s))")
                continue
            normalized = _normalize(content)
            if len(normalized) < MIN_SECTION_CHARS:
                report(f"runbooks/{rb_name}: '## {heading}' section is empty or "
                       f"too short to be real content")
            elif normalized == _TEMPLATE_PLACEHOLDER.get(heading):
                report(f"runbooks/{rb_name}: '## {heading}' section is still the "
                       f"literal TEMPLATE.md boilerplate, never filled in")


def check_orphaned_runbooks(referenced_runbooks: set[str]) -> None:
    skip = {"README.md", "TEMPLATE.md"}
    dashboard_runbooks: set[str] = set()
    for p in sorted(DASHBOARDS.glob("*.json")):
        try:
            doc = json.loads(p.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            continue
        rb = (doc.get("__opsmind_meta") or {}).get("runbook", "")
        m = RUNBOOK_URL_RE.search(rb) or (re.match(r"^([A-Za-z0-9_.-]+\.md)$", rb) if rb else None)
        if isinstance(m, re.Match):
            dashboard_runbooks.add(m.group(1))
    for p in sorted(RUNBOOKS.glob("*.md")):
        if p.name in skip:
            continue
        if p.name not in referenced_runbooks and p.name not in dashboard_runbooks:
            warn(f"runbooks/{p.name} is not referenced by any alert's runbook_url "
                 f"or dashboard __opsmind_meta.runbook — orphaned catalog entry")


def main() -> int:
    if yaml is None:
        print("ERROR validate-rule-catalog: PyYAML not installed "
              "(pip install pyyaml / uv run --with pyyaml)")
        return 1
    if not RULES_ALERTING.is_dir() or not RULES_RECORDING.is_dir():
        err("missing rules/alerting or rules/recording directory")
        return 1

    referenced_runbooks: set[str] = set()
    runbook_severities: dict[str, set[str]] = {}
    for p in sorted(RULES_ALERTING.glob("*.yml")):
        check_alerting_file(p, referenced_runbooks, runbook_severities)
    for p in sorted(RULES_RECORDING.glob("*.yml")):
        check_recording_file(p)
    if LOKI_RULES.is_dir():
        for p in sorted(LOKI_RULES.rglob("*.yaml")):
            # Loki ruler alert rules use the identical {alert, labels, annotations}
            # shape as Prometheus's own — same checks apply, same runbook contract.
            check_alerting_file(p, referenced_runbooks, runbook_severities)
    check_runbook_structure(runbook_severities)
    check_orphaned_runbooks(referenced_runbooks)

    for w in warnings:
        print(f"WARN  {w}")
    for e in errors:
        print(f"ERROR {e}")
    print()
    print(f"validate-rule-catalog: {len(errors)} error(s), {len(warnings)} warning(s)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
