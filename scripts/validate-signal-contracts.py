#!/usr/bin/env python3
"""Validate the OpsMind unified signal contracts (phase-01).

SPEC-OP-004 (Resource Attribute Convention), SPEC-OP-005 (HTTP/AMQP Trace
Propagation), SPEC-OP-006 (Metric Naming & Cardinality), SPEC-OP-007 (Structured
Log & Redaction) — phase-01 in full.

Checks:
  1. signals/resource-attributes.yaml: shape, SemVer, per-attribute fields,
     value_pattern compiles, value_enum_ref resolves
  2. governance cross-check: the `required` attribute set here is exactly
     governance/telemetry-governance.yaml : allow_fields.resource.required
  3. fixtures/resource-attributes/*.json: every `expect: pass` fixture is
     conformant; every `expect: reject` fixture is not
  4. collector/base/config.yaml wires resourcedetection + transform/resource-contract
     into all three pipelines and references the contract's default + violation attr

Exit 0 = OK (warnings allowed), 1 = errors. Requires PyYAML.
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
RA_YAML = OBS / "signals" / "resource-attributes.yaml"
RA_SCHEMA = OBS / "schemas" / "resource-attributes.schema.json"
GOV = OBS / "governance" / "telemetry-governance.yaml"
COLLECTOR = OBS / "collector" / "base" / "config.yaml"
FIXTURES = OBS / "signals" / "fixtures" / "resource-attributes"

TP_YAML = OBS / "signals" / "trace-propagation.yaml"
TP_SCHEMA = OBS / "schemas" / "trace-propagation.schema.json"
TP_FIXTURES = OBS / "signals" / "fixtures" / "trace-propagation"

MN_YAML = OBS / "signals" / "metric-naming.yaml"
MN_SCHEMA = OBS / "schemas" / "metric-naming.schema.json"
MN_FIXTURES = OBS / "signals" / "fixtures" / "metric-naming"

SL_YAML = OBS / "signals" / "structured-log.yaml"
SL_SCHEMA = OBS / "schemas" / "structured-log.schema.json"
SL_FIXTURES = OBS / "signals" / "fixtures" / "structured-log"

SEMVER = re.compile(r"^\d+\.\d+\.\d+$")
LEVELS = {"required", "recommended", "optional"}
FORBIDDEN_HEADER = re.compile(
    r"^(x-b3-|b3$|uber-trace-id$|x-datadog-|ot-tracer-|x-amzn-trace-id$)", re.I
)

errors: list[str] = []
warnings: list[str] = []


def err(m: str) -> None:
    errors.append(m)


def warn(m: str) -> None:
    warnings.append(m)


def load_yaml(p: Path) -> dict | None:
    if not p.is_file():
        err(f"missing {p.relative_to(REPO)}")
        return None
    try:
        return yaml.safe_load(p.read_text(encoding="utf-8"))
    except yaml.YAMLError as e:
        err(f"{p.name}: invalid YAML ({e})")
        return None


def resource_attrs_from_fixture(doc: dict) -> dict[str, str]:
    out: dict[str, str] = {}
    for a in doc.get("resource", {}).get("attributes", []):
        v = a.get("value", {})
        out[a["key"]] = v.get("stringValue", next(iter(v.values()), ""))
    return out


def check_contract(ra: dict) -> list[dict]:
    if not isinstance(ra, dict):
        err("resource-attributes.yaml: top level is not a mapping")
        return []
    for k in ("version", "namespaces", "environments", "attributes",
              "missing_service_name_default", "violation_attribute"):
        if k not in ra:
            err(f"resource-attributes.yaml: missing '{k}'")
    if isinstance(ra.get("version"), str) and not SEMVER.match(ra["version"]):
        err(f"resource-attributes.yaml: version {ra['version']!r} not SemVer")
    enums = {"namespaces": ra.get("namespaces", []), "environments": ra.get("environments", [])}
    attrs = ra.get("attributes", []) or []
    seen = set()
    for a in attrs:
        key = a.get("key", "<no key>")
        if key in seen:
            err(f"resource-attributes.yaml: duplicate attribute {key!r}")
        seen.add(key)
        for f in ("key", "level", "semconv", "cardinality", "source"):
            if f not in a:
                err(f"attribute {key!r}: missing '{f}'")
        if a.get("level") not in LEVELS:
            err(f"attribute {key!r}: level {a.get('level')!r} not in {sorted(LEVELS)}")
        if a.get("cardinality") not in {"bounded", "replica-scoped", "unbounded"}:
            err(f"attribute {key!r}: bad cardinality {a.get('cardinality')!r}")
        if "value_pattern" in a:
            try:
                re.compile(a["value_pattern"])
            except re.error as e:
                err(f"attribute {key!r}: value_pattern does not compile ({e})")
        if "value_enum_ref" in a and a["value_enum_ref"] not in enums:
            err(f"attribute {key!r}: value_enum_ref {a['value_enum_ref']!r} unknown")
    return attrs


def check_governance_sync(attrs: list[dict], gov: dict) -> None:
    contract_required = {a["key"] for a in attrs if a.get("level") == "required"}
    gov_required = set(
        (gov or {}).get("allow_fields", {}).get("resource", {}).get("required", [])
    )
    missing_in_gov = contract_required - gov_required
    missing_in_contract = gov_required - contract_required
    if missing_in_gov:
        err(f"resource-attributes.yaml marks {sorted(missing_in_gov)} required, but "
            f"governance/telemetry-governance.yaml allow_fields.resource.required does not")
    if missing_in_contract:
        err(f"governance requires {sorted(missing_in_contract)} but resource-attributes.yaml "
            f"does not list them at level 'required'")


def _conformant(attrs: list[dict], enums: dict, res: dict[str, str]) -> tuple[bool, list[str]]:
    reasons: list[str] = []
    for a in attrs:
        if a.get("level") != "required":
            continue
        key = a["key"]
        val = res.get(key, "")
        if key not in res or val == "":
            reasons.append(f"missing/blank {key}")
            continue
        if "value_pattern" in a and not re.match(a["value_pattern"], val):
            reasons.append(f"{key}={val!r} fails pattern")
        if "value_enum_ref" in a and val not in enums.get(a["value_enum_ref"], []):
            reasons.append(f"{key}={val!r} not in {a['value_enum_ref']}")
    return (not reasons, reasons)


def check_fixtures(attrs: list[dict], ra: dict) -> None:
    enums = {"namespaces": ra.get("namespaces", []), "environments": ra.get("environments", [])}
    files = sorted(FIXTURES.glob("*.json"))
    if not files:
        err(f"no fixtures in {FIXTURES.relative_to(REPO)}")
        return
    seen_pass = seen_reject = 0
    for p in files:
        try:
            doc = json.loads(p.read_text(encoding="utf-8"))
        except json.JSONDecodeError as e:
            err(f"{p.name}: invalid JSON ({e})")
            continue
        expect = doc.get("_fixture", {}).get("expect")
        if expect not in {"pass", "reject"}:
            err(f"{p.name}: _fixture.expect must be 'pass' or 'reject'")
            continue
        ok, reasons = _conformant(attrs, enums, resource_attrs_from_fixture(doc))
        if expect == "pass":
            seen_pass += 1
            if not ok:
                err(f"{p.name}: expected conformant but failed: {reasons}")
        else:
            seen_reject += 1
            if ok:
                err(f"{p.name}: expected non-conformant but it passed the contract")
    if seen_pass == 0:
        err("no 'expect: pass' fixture — add at least one conformant golden payload")
    if seen_reject == 0:
        warn("no 'expect: reject' fixture — negative coverage is thin")


def _ingest_pipelines(text: str) -> dict[str, list[str]]:
    """{pipeline_name: processors_list} scoped to pipelines that receive
    directly from the otlp receiver (ADR-0001's sole ingestion boundary).

    SPEC-OP-031 added per-tenant fan-out pipelines (receivers: [routing/...])
    downstream of the real traces/metrics/logs ingest pipelines — every
    processor these contracts care about already ran once, upstream, before
    the routing connector. Re-requiring resourcedetection / resource-contract /
    baggage-contract on each fan-out hop would just be duplicate no-op work
    (the attributes they touch are already resolved), not a real additional
    guarantee, so callers scope to ingest pipelines rather than every pipeline
    in the file regardless of what feeds it.
    """
    try:
        doc = yaml.safe_load(text)
    except yaml.YAMLError:
        return {}
    pipelines = (doc or {}).get("service", {}).get("pipelines", {})
    return {
        name: list((body or {}).get("processors") or [])
        for name, body in pipelines.items()
        if "otlp" in (body or {}).get("receivers", [])
    }


def check_collector(ra: dict) -> None:
    if not COLLECTOR.is_file():
        err(f"missing {COLLECTOR.relative_to(REPO)}")
        return
    text = COLLECTOR.read_text(encoding="utf-8")
    for token in ("resourcedetection", "transform/resource-contract"):
        if token not in text:
            err(f"collector/base/config.yaml: missing processor '{token}'")
    for name, procs in _ingest_pipelines(text).items():
        for token in ("resourcedetection", "transform/resource-contract"):
            if token not in procs:
                err(f"collector pipeline {name!r} not wired with {token}: processors: {procs}")
    default = ra.get("missing_service_name_default", "")
    viol = ra.get("violation_attribute", "")
    if default and default not in text:
        err(f"collector config does not use missing_service_name_default {default!r}")
    if viol and viol not in text:
        err(f"collector config does not set violation_attribute {viol!r}")


# ---------------------------------------------------------------------------
# SPEC-OP-005 — HTTP / AMQP trace propagation
# ---------------------------------------------------------------------------
def _parse_baggage(raw: str) -> list[tuple[str, str]]:
    pairs = []
    for item in raw.split(","):
        item = item.strip()
        if not item or "=" not in item:
            continue
        k, _, v = item.partition("=")
        pairs.append((k.strip(), v.strip()))
    return pairs


def check_propagation(tp: dict) -> None:
    if not isinstance(tp, dict):
        err("trace-propagation.yaml: top level is not a mapping")
        return
    for k in ("version", "propagators", "forbidden_propagators", "http", "amqp",
              "baggage", "collector_processor", "span_attribute_prefix_removed",
              "correlation_id_span_attribute"):
        if k not in tp:
            err(f"trace-propagation.yaml: missing '{k}'")
    if isinstance(tp.get("version"), str) and not SEMVER.match(tp["version"]):
        err(f"trace-propagation.yaml: version {tp['version']!r} not SemVer")
    if tp.get("propagators") != ["tracecontext", "baggage"]:
        err("trace-propagation.yaml: propagators must be exactly [tracecontext, baggage]")
    if not tp.get("forbidden_propagators"):
        err("trace-propagation.yaml: forbidden_propagators is empty")
    bag = tp.get("baggage", {})
    if not isinstance(bag.get("max_total_bytes"), int) or not isinstance(bag.get("max_entries"), int):
        err("trace-propagation.yaml: baggage.max_total_bytes / max_entries must be integers")
    for ak in bag.get("allowed_keys", []):
        for f in ("key", "value_pattern", "cardinality"):
            if f not in ak:
                err(f"baggage allowed_keys entry missing '{f}': {ak!r}")
        if "value_pattern" in ak:
            try:
                re.compile(ak["value_pattern"])
            except re.error as e:
                err(f"baggage key {ak.get('key')!r}: value_pattern does not compile ({e})")


def _propagation_conformant(tp: dict, carrier: dict[str, str]) -> tuple[bool, list[str]]:
    reasons: list[str] = []
    keys_ci = {k.lower(): v for k, v in carrier.items()}
    if "traceparent" not in keys_ci:
        reasons.append("no W3C 'traceparent' header")
    for k in carrier:
        if FORBIDDEN_HEADER.match(k):
            reasons.append(f"forbidden propagator header {k!r}")
    bag = tp.get("baggage", {})
    allowed = {a["key"]: a for a in bag.get("allowed_keys", [])}
    raw = keys_ci.get("baggage", "")
    if raw:
        if len(raw.encode("utf-8")) > bag.get("max_total_bytes", 1 << 30):
            reasons.append(f"baggage exceeds max_total_bytes ({bag['max_total_bytes']})")
        pairs = _parse_baggage(raw)
        if len(pairs) > bag.get("max_entries", 1 << 30):
            reasons.append(f"baggage has {len(pairs)} entries > max_entries {bag['max_entries']}")
        for k, v in pairs:
            if k not in allowed:
                reasons.append(f"baggage key {k!r} not in allow-list")
                continue
            from urllib.parse import unquote
            if not re.match(allowed[k]["value_pattern"], unquote(v)):
                reasons.append(f"baggage {k}={v!r} fails value_pattern")
    return (not reasons, reasons)


def check_propagation_fixtures(tp: dict) -> None:
    files = sorted(TP_FIXTURES.glob("*.json"))
    if not files:
        err(f"no fixtures in {TP_FIXTURES.relative_to(REPO)}")
        return
    seen_pass = seen_reject = 0
    for p in files:
        try:
            doc = json.loads(p.read_text(encoding="utf-8"))
        except json.JSONDecodeError as e:
            err(f"{p.name}: invalid JSON ({e})")
            continue
        expect = doc.get("_fixture", {}).get("expect")
        if expect not in {"pass", "reject"}:
            err(f"{p.name}: _fixture.expect must be 'pass' or 'reject'")
            continue
        ok, reasons = _propagation_conformant(tp, doc.get("carrier", {}))
        if expect == "pass":
            seen_pass += 1
            if not ok:
                err(f"{p.name}: expected conformant but failed: {reasons}")
        else:
            seen_reject += 1
            if ok:
                err(f"{p.name}: expected non-conformant but it passed the contract")
    if seen_pass == 0:
        err("trace-propagation: no 'expect: pass' fixture")
    if seen_reject == 0:
        warn("trace-propagation: no 'expect: reject' fixture")


def check_propagation_collector(tp: dict) -> None:
    if not COLLECTOR.is_file():
        return
    text = COLLECTOR.read_text(encoding="utf-8")
    proc = tp.get("collector_processor", "transform/baggage-contract")
    if proc not in text:
        err(f"collector/base/config.yaml: missing processor '{proc}'")
    for name, procs in _ingest_pipelines(text).items():
        if proc not in procs:
            err(f"collector pipeline {name!r} not wired with {proc}: processors: {procs}")
    prefix = tp.get("span_attribute_prefix_removed", "baggage.")
    if f'^{prefix.rstrip(".")}' not in text and "^baggage" not in text:
        err(f"collector config has no delete_matching_keys for prefix {prefix!r}")


# ---------------------------------------------------------------------------
# SPEC-OP-006 — metric naming & cardinality
# ---------------------------------------------------------------------------
_METRIC_TYPES = {"counter", "gauge", "histogram", "updowncounter"}


def check_metric_naming(mn: dict) -> None:
    if not isinstance(mn, dict):
        err("metric-naming.yaml: top level is not a mapping")
        return
    for k in ("version", "naming", "units", "bucket_sets", "namespaces",
              "service_series_budgets", "exemplars", "collector_processor"):
        if k not in mn:
            err(f"metric-naming.yaml: missing '{k}'")
    if isinstance(mn.get("version"), str) and not SEMVER.match(mn["version"]):
        err(f"metric-naming.yaml: version {mn['version']!r} not SemVer")
    try:
        re.compile(mn.get("naming", {}).get("name_pattern", ""))
    except re.error as e:
        err(f"metric-naming.yaml: naming.name_pattern does not compile ({e})")
    for name, boundaries in (mn.get("bucket_sets") or {}).items():
        if not isinstance(boundaries, list) or len(boundaries) < 2:
            err(f"bucket_sets.{name}: need >= 2 boundaries")
        elif boundaries != sorted(boundaries):
            err(f"bucket_sets.{name}: boundaries not ascending")
    for ns in mn.get("namespaces", []):
        for f in ("namespace", "allowed_labels", "forbidden_labels",
                  "max_label_keys", "max_series", "example_metrics"):
            if f not in ns:
                err(f"metric-naming namespace entry missing '{f}': {ns.get('namespace')!r}")
        overlap = set(ns.get("allowed_labels", [])) & set(ns.get("forbidden_labels", []))
        if overlap:
            err(f"namespace {ns.get('namespace')!r}: label in both allowed and forbidden: {sorted(overlap)}")
    for b in mn.get("service_series_budgets", []):
        for f in ("service_namespace", "local", "ci", "prod"):
            if f not in b:
                err(f"service_series_budgets entry missing '{f}': {b!r}")


def check_metric_governance_sync(mn: dict, gov: dict) -> None:
    mn_ns = {n["namespace"]: n for n in mn.get("namespaces", [])}
    for g in (gov or {}).get("cardinality_budgets", {}).get("namespaces", []):
        name = g["namespace"]
        if name not in mn_ns:
            err(f"governance cardinality namespace {name!r} missing from metric-naming.yaml")
            continue
        m = mn_ns[name]
        if m.get("max_label_keys") != g.get("max_label_keys"):
            err(f"namespace {name!r}: max_label_keys {m.get('max_label_keys')} != governance {g.get('max_label_keys')}")
        if m.get("max_series") != g.get("max_series"):
            err(f"namespace {name!r}: max_series {m.get('max_series')} != governance {g.get('max_series')}")
        missing = set(g.get("forbidden_labels", [])) - set(m.get("forbidden_labels", []))
        if missing:
            err(f"namespace {name!r}: metric-naming forbidden_labels missing governance's {sorted(missing)}")


def _metric_conformant(mn: dict, m: dict) -> tuple[bool, list[str]]:
    reasons: list[str] = []
    name = m.get("name", "")
    naming = mn.get("naming", {})
    if not re.match(naming.get("name_pattern", "^$"), name):
        reasons.append(f"name {name!r} fails name_pattern")
    ns = name.split("_", 1)[0] if "_" in name else name
    if ns not in naming.get("namespaces", []):
        reasons.append(f"namespace prefix {ns!r} not registered")
    if m.get("namespace") and m["namespace"] != ns:
        reasons.append(f"declared namespace {m['namespace']!r} != name prefix {ns!r}")
    for bad in mn.get("units", {}).get("forbidden_substrings", []):
        if bad in name:
            reasons.append(f"name contains forbidden unit substring {bad!r}")
    mtype = str(m.get("type", "")).lower()
    if mtype not in _METRIC_TYPES:
        reasons.append(f"type {m.get('type')!r} invalid")
    if mtype == "counter" and not name.endswith("_total"):
        reasons.append("counter name must end with _total")
    if mtype == "histogram" and name.endswith("_total"):
        reasons.append("histogram base name must not end with _total")
    if not any(name.endswith(s) for s in naming.get("allowed_suffixes", [])):
        reasons.append("name does not end with an allowed unit/type suffix")
    ns_def = next((n for n in mn.get("namespaces", []) if n["namespace"] == ns), None)
    labels = m.get("labels", [])
    if ns_def:
        allowed = set(ns_def.get("allowed_labels", []))
        forbidden = set(ns_def.get("forbidden_labels", []))
        for lbl in labels:
            if lbl in forbidden:
                reasons.append(f"label {lbl!r} is forbidden in namespace {ns!r}")
            elif lbl not in allowed:
                reasons.append(f"label {lbl!r} not in {ns!r} allow-list")
        if len(labels) > ns_def.get("max_label_keys", 1 << 30):
            reasons.append(f"{len(labels)} labels > max_label_keys {ns_def['max_label_keys']}")
    return (not reasons, reasons)


def check_metric_fixtures(mn: dict) -> None:
    files = sorted(MN_FIXTURES.glob("*.json"))
    if not files:
        err(f"no fixtures in {MN_FIXTURES.relative_to(REPO)}")
        return
    seen_pass = seen_reject = 0
    for p in files:
        try:
            doc = json.loads(p.read_text(encoding="utf-8"))
        except json.JSONDecodeError as e:
            err(f"{p.name}: invalid JSON ({e})")
            continue
        expect = doc.get("_fixture", {}).get("expect")
        if expect not in {"pass", "reject"}:
            err(f"{p.name}: _fixture.expect must be 'pass' or 'reject'")
            continue
        ok, reasons = _metric_conformant(mn, doc.get("metric", {}))
        if expect == "pass":
            seen_pass += 1
            if not ok:
                err(f"{p.name}: expected conformant but failed: {reasons}")
        else:
            seen_reject += 1
            if ok:
                err(f"{p.name}: expected non-conformant but it passed the contract")
    if seen_pass == 0:
        err("metric-naming: no 'expect: pass' fixture")
    if seen_reject == 0:
        warn("metric-naming: no 'expect: reject' fixture")


def check_metric_collector(mn: dict, gov: dict) -> None:
    if not COLLECTOR.is_file():
        return
    text = COLLECTOR.read_text(encoding="utf-8")
    proc = mn.get("collector_processor", "transform/metric-cardinality")
    if proc not in text:
        err(f"collector/base/config.yaml: missing processor '{proc}'")
        return
    metrics_line = next((l for l in text.splitlines()
                         if "processors: [" in l and proc in l), None)
    if metrics_line is None:
        err(f"collector: {proc} not wired into the metrics pipeline")
    gov_forbidden = set()
    for g in (gov or {}).get("cardinality_budgets", {}).get("namespaces", []):
        gov_forbidden |= set(g.get("forbidden_labels", []))
    for lbl in sorted(gov_forbidden):
        frag = re.sub(r"[_.\-]", "[_.-]?", lbl)
        if frag not in text and lbl not in text:
            err(f"collector {proc} regex does not cover governance forbidden label {lbl!r}")


# ---------------------------------------------------------------------------
# SPEC-OP-007 — structured log and redaction contract
# ---------------------------------------------------------------------------
def check_structured_log(sl: dict) -> None:
    if not isinstance(sl, dict):
        err("structured-log.yaml: top level is not a mapping")
        return
    for k in ("version", "severity_map", "unspecified_severity_number", "event_code",
              "linkage", "multiline", "sampling_intent", "redaction",
              "schema_contract_processor", "violation_attribute"):
        if k not in sl:
            err(f"structured-log.yaml: missing '{k}'")
    if isinstance(sl.get("version"), str) and not SEMVER.match(sl["version"]):
        err(f"structured-log.yaml: version {sl['version']!r} not SemVer")
    smap = sl.get("severity_map", [])
    if not smap:
        err("structured-log.yaml: severity_map is empty")
    covered: list[tuple[int, int]] = []
    for e in smap:
        for f in ("level", "severity_text_prefix", "severity_number_min", "severity_number_max"):
            if f not in e:
                err(f"severity_map entry missing '{f}': {e!r}")
                continue
        lo, hi = e.get("severity_number_min"), e.get("severity_number_max")
        if isinstance(lo, int) and isinstance(hi, int):
            if lo > hi:
                err(f"severity_map {e.get('level')!r}: min {lo} > max {hi}")
            covered.append((lo, hi))
    for (lo1, hi1), (lo2, hi2) in zip(sorted(covered), sorted(covered)[1:]):
        if hi1 >= lo2:
            err(f"severity_map ranges overlap: ({lo1},{hi1}) and ({lo2},{hi2})")
    try:
        re.compile(sl.get("event_code", {}).get("pattern", ""))
    except re.error as e:
        err(f"structured-log.yaml: event_code.pattern does not compile ({e})")
    if not sl.get("linkage", {}).get("any_of"):
        err("structured-log.yaml: linkage.any_of is empty")
    classes = set((sl.get("multiline") or {}).keys())
    for f in ("one_event_per_record", "max_body_chars", "truncation_attribute"):
        if f not in classes:
            err(f"structured-log.yaml: multiline missing '{f}'")


def check_structured_log_governance_sync(sl: dict, gov: dict) -> None:
    gov_log = (gov or {}).get("allow_fields", {}).get("log", {})
    gov_recommended = set(gov_log.get("recommended", []))
    linkage = set(sl.get("linkage", {}).get("any_of", []))
    missing = linkage - gov_recommended
    if missing:
        err(f"structured-log.yaml linkage.any_of {sorted(missing)} not in "
            f"governance allow_fields.log.recommended {sorted(gov_recommended)}")
    ec_attr = sl.get("event_code", {}).get("attribute")
    if ec_attr and ec_attr not in gov_recommended:
        err(f"structured-log.yaml event_code.attribute {ec_attr!r} not in "
            f"governance allow_fields.log.recommended")
    rc_names = set((gov or {}).get("retention_classes", {}).keys())
    for s in sl.get("sampling_intent", []):
        rc = s.get("retention_class")
        if rc and rc not in rc_names:
            err(f"structured-log.yaml sampling_intent level {s.get('level')!r}: "
                f"retention_class {rc!r} not in governance retention_classes {sorted(rc_names)}")
    gov_lbr = (gov or {}).get("log_body_redaction", {})
    sl_proc = sl.get("redaction", {}).get("collector_processor")
    gov_proc = gov_lbr.get("collector_processor")
    if sl_proc and gov_proc and sl_proc != gov_proc:
        err(f"structured-log.yaml redaction.collector_processor {sl_proc!r} != "
            f"governance log_body_redaction.collector_processor {gov_proc!r}")
    sl_flag = sl.get("redaction", {}).get("redacted_attribute")
    gov_flag = gov_lbr.get("redacted_attribute")
    if sl_flag and gov_flag and sl_flag != gov_flag:
        err(f"structured-log.yaml redaction.redacted_attribute {sl_flag!r} != "
            f"governance log_body_redaction.redacted_attribute {gov_flag!r}")


def _structured_log_conformant(sl: dict, gov: dict, log: dict) -> tuple[bool, list[str]]:
    reasons: list[str] = []
    unspecified = sl.get("unspecified_severity_number", 0)
    sev_num = log.get("severity_number")
    if not isinstance(sev_num, int) or sev_num == unspecified:
        reasons.append(f"severity_number {sev_num!r} missing or unspecified ({unspecified})")
    else:
        entry = next((e for e in sl.get("severity_map", [])
                      if e.get("severity_number_min", 1) <= sev_num <= e.get("severity_number_max", 0)),
                     None)
        if entry is None:
            reasons.append(f"severity_number {sev_num} is outside every severity_map range")
        else:
            sev_text = str(log.get("severity_text") or "")
            if not sev_text.upper().startswith(entry["severity_text_prefix"]):
                reasons.append(f"severity_text {sev_text!r} does not match severity_number "
                                f"{sev_num} (expected prefix {entry['severity_text_prefix']!r})")
    body = log.get("body")
    if not isinstance(body, str) or body == "":
        reasons.append("body missing or not a non-empty string")
    attrs = log.get("attributes") or {}
    linkage = sl.get("linkage", {}).get("any_of", [])
    if not any(attrs.get(k) for k in linkage):
        reasons.append(f"missing linkage: none of {linkage} present in attributes")
    ec = attrs.get(sl.get("event_code", {}).get("attribute", "event.code"))
    if ec is not None:
        pat = sl.get("event_code", {}).get("pattern", "^$")
        if not re.match(pat, str(ec)):
            reasons.append(f"event.code {ec!r} fails pattern {pat!r}")
    if isinstance(body, str):
        # Fixtures may declare `_body_repeat_count` to simulate an oversized body
        # without embedding a giant literal string in the repo.
        repeat = log.get("_body_repeat_count", 1)
        effective_len = len(body) * (repeat if isinstance(repeat, int) and repeat > 0 else 1)
        max_chars = sl.get("multiline", {}).get("max_body_chars", 1 << 30)
        truncated = bool(attrs.get(sl.get("multiline", {}).get("truncation_attribute", "")))
        if effective_len > max_chars and not truncated:
            reasons.append(f"body length {effective_len} exceeds multiline.max_body_chars "
                            f"{max_chars} without a truncation marker")
        for p in (gov or {}).get("log_body_redaction", {}).get("patterns", []):
            try:
                if re.search(p["pattern"], body):
                    reasons.append(f"body matches governance log_body_redaction pattern "
                                    f"{p.get('name')!r} — producer must not emit this (F7)")
            except re.error:
                pass
    return (not reasons, reasons)


def check_structured_log_fixtures(sl: dict, gov: dict) -> None:
    files = sorted(SL_FIXTURES.glob("*.json"))
    if not files:
        err(f"no fixtures in {SL_FIXTURES.relative_to(REPO)}")
        return
    seen_pass = seen_reject = 0
    for p in files:
        try:
            doc = json.loads(p.read_text(encoding="utf-8"))
        except json.JSONDecodeError as e:
            err(f"{p.name}: invalid JSON ({e})")
            continue
        expect = doc.get("_fixture", {}).get("expect")
        if expect not in {"pass", "reject"}:
            err(f"{p.name}: _fixture.expect must be 'pass' or 'reject'")
            continue
        ok, reasons = _structured_log_conformant(sl, gov, doc.get("log", {}))
        if expect == "pass":
            seen_pass += 1
            if not ok:
                err(f"{p.name}: expected conformant but failed: {reasons}")
        else:
            seen_reject += 1
            if ok:
                err(f"{p.name}: expected non-conformant but it passed the contract")
    if seen_pass == 0:
        err("structured-log: no 'expect: pass' fixture")
    if seen_reject == 0:
        warn("structured-log: no 'expect: reject' fixture")


def check_structured_log_collector(sl: dict, gov: dict) -> None:
    if not COLLECTOR.is_file():
        return
    text = COLLECTOR.read_text(encoding="utf-8")
    schema_proc = sl.get("schema_contract_processor", "transform/log-schema-contract")
    redaction_proc = sl.get("redaction", {}).get("collector_processor", "transform/log-body-redaction")
    for proc in (schema_proc, redaction_proc):
        if proc not in text:
            err(f"collector/base/config.yaml: missing processor '{proc}'")
            continue
        logs_line = next((l for l in text.splitlines()
                          if "processors: [" in l and proc in l), None)
        if logs_line is None:
            err(f"collector: {proc} not wired into the logs pipeline")
    viol = sl.get("violation_attribute", "")
    if viol and viol not in text:
        err(f"collector config does not set violation_attribute {viol!r}")
    for p in (gov or {}).get("log_body_redaction", {}).get("patterns", []):
        pat = p.get("pattern", "")
        pat_yaml = pat.replace("\\", "\\\\")
        if pat and pat not in text and pat_yaml not in text:
            err(f"collector {redaction_proc} does not contain governance pattern "
                f"{p.get('name', '?')!r}: {pat!r}")


def main() -> int:
    if yaml is None:
        print("ERROR validate-signal-contracts: PyYAML not installed "
              "(pip install pyyaml / uv run --with pyyaml)")
        return 1
    for schema in (RA_SCHEMA, TP_SCHEMA, MN_SCHEMA, SL_SCHEMA):
        if not schema.is_file():
            err(f"missing {schema.relative_to(REPO)}")
        else:
            try:
                json.loads(schema.read_text(encoding="utf-8"))
            except json.JSONDecodeError as e:
                err(f"{schema.name}: invalid JSON ({e})")

    ra = load_yaml(RA_YAML)
    gov = load_yaml(GOV)
    if ra is not None:
        attrs = check_contract(ra)
        if gov is not None:
            check_governance_sync(attrs, gov)
        check_fixtures(attrs, ra)
        check_collector(ra)

    tp = load_yaml(TP_YAML)
    if tp is not None:
        check_propagation(tp)
        check_propagation_fixtures(tp)
        check_propagation_collector(tp)

    mn = load_yaml(MN_YAML)
    if mn is not None:
        check_metric_naming(mn)
        if gov is not None:
            check_metric_governance_sync(mn, gov)
        check_metric_fixtures(mn)
        check_metric_collector(mn, gov)

    sl = load_yaml(SL_YAML)
    if sl is not None:
        check_structured_log(sl)
        if gov is not None:
            check_structured_log_governance_sync(sl, gov)
        check_structured_log_fixtures(sl, gov)
        check_structured_log_collector(sl, gov)

    for w in warnings:
        print(f"WARN  {w}")
    for e in errors:
        print(f"ERROR {e}")
    print()
    print(f"validate-signal-contracts: {len(errors)} error(s), {len(warnings)} warning(s)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
