#!/usr/bin/env python3
"""Validate the OpsMind Collector's processor ORDER contract (SPEC-OP-009).

Every earlier phase-01/02 spec appended a processor to collector/base/config.yaml and
asserted it was PRESENT in each pipeline (validate-signal-contracts.py). Nothing ever
asserted the processors run in the CORRECT RELATIVE ORDER — a future edit could
silently reorder two processors (e.g. run transform/governance before
transform/resource-contract, so the deny-list scrubs a key the contract check then
never sees) and every existing check would still pass. This script closes that gap.

Checks:
  1. Every pipeline's processor list starts with memory_limiter and ends with batch.
  2. transform/governance (the deny-list floor) is always second-to-last (immediately
     before batch) — nothing may run after the deny-list has been applied.
  3. Within each pipeline, the processors present appear in the SAME RELATIVE ORDER as
     MASTER_ORDER below (a topological-order check, not a hand-authored per-pipeline
     list — robust against a future processor being added in the wrong place).
  4. A processor restricted to one signal type (SIGNAL_ONLY) never appears in a
     pipeline it doesn't belong to.

Exit 0 = OK, 1 = one or more errors. Requires PyYAML.
"""
from __future__ import annotations

import sys
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover
    yaml = None

REPO = Path(__file__).resolve().parents[1]
COLLECTOR = REPO / "infrastructure" / "observability" / "collector" / "base" / "config.yaml"

# The canonical order every pipeline's processor list must be a (relatively-ordered)
# subsequence of. Update this — and only this — when a new processor is added.
MASTER_ORDER = [
    "memory_limiter",
    "resourcedetection",
    "resource",
    "transform/resource-contract",
    "transform/baggage-contract",
    "filter/noise",
    "attributes/semconv-compat",
    "transform/metric-cardinality",
    "transform/trace-priority",
    "transform/log-schema-contract",
    "transform/log-body-redaction",
    "transform/governance",
    "tail_sampling",
    "batch",
]

# Processors that must only ever appear in ONE named pipeline.
SIGNAL_ONLY = {
    "transform/metric-cardinality": "metrics",
    "transform/trace-priority": "traces",
    "tail_sampling": "traces",
    "transform/log-schema-contract": "logs",
    "transform/log-body-redaction": "logs",
}

# transform/governance (the deny-list floor) must be the last processor that can
# still MUTATE attribute/body VALUES. Only a decision-only processor (keep/drop,
# never rewrites a value) may run between it and the final `batch` — currently just
# tail_sampling (SPEC-OP-010: buffers a whole trace and decides keep/drop, touches no
# attribute value). Adding anything else here needs a deliberate review: it runs
# AFTER redaction has already happened.
ALLOWED_AFTER_GOVERNANCE = {"tail_sampling"}

errors: list[str] = []
warnings: list[str] = []


def err(m: str) -> None:
    errors.append(m)


def warn(m: str) -> None:
    warnings.append(m)


def load_pipelines() -> dict[str, list[str]] | None:
    if not COLLECTOR.is_file():
        err(f"missing {COLLECTOR.relative_to(REPO)}")
        return None
    try:
        doc = yaml.safe_load(COLLECTOR.read_text(encoding="utf-8"))
    except yaml.YAMLError as e:
        err(f"config.yaml: invalid YAML ({e})")
        return None
    pipelines = (doc or {}).get("service", {}).get("pipelines", {})
    if not pipelines:
        err("config.yaml: service.pipelines is empty or missing")
        return None
    out: dict[str, list[str]] = {}
    for name, body in pipelines.items():
        procs = body.get("processors")
        if procs is None:
            err(f"pipeline {name!r}: no 'processors' key")
            continue
        out[name] = list(procs)
    return out


def check_master_order_shape() -> None:
    if len(set(MASTER_ORDER)) != len(MASTER_ORDER):
        err("MASTER_ORDER contains a duplicate entry")
    if MASTER_ORDER[0] != "memory_limiter":
        err("MASTER_ORDER must start with memory_limiter")
    if MASTER_ORDER[-1] != "batch":
        err("MASTER_ORDER must end with batch")
    between = MASTER_ORDER[MASTER_ORDER.index("transform/governance") + 1:-1]
    not_allowed = [p for p in between if p not in ALLOWED_AFTER_GOVERNANCE]
    if not_allowed:
        err(f"MASTER_ORDER has value-mutating processor(s) after transform/governance: "
            f"{not_allowed} — only ALLOWED_AFTER_GOVERNANCE (decision-only) processors may run there")


def check_pipeline(name: str, procs: list[str]) -> None:
    if not procs:
        err(f"pipeline {name!r}: empty processor list")
        return
    if procs[0] != "memory_limiter":
        err(f"pipeline {name!r}: must start with memory_limiter, starts with {procs[0]!r}")
    if procs[-1] != "batch":
        err(f"pipeline {name!r}: must end with batch, ends with {procs[-1]!r}")
    if "transform/governance" not in procs:
        err(f"pipeline {name!r}: transform/governance (deny-list floor) is missing")
    else:
        after_gov = procs[procs.index("transform/governance") + 1:-1]
        not_allowed = [p for p in after_gov if p not in ALLOWED_AFTER_GOVERNANCE]
        if not_allowed:
            err(f"pipeline {name!r}: value-mutating processor(s) run AFTER "
                f"transform/governance: {not_allowed} — redaction must be the last "
                f"step that can still change a value")

    # Relative-order check: the processors this pipeline actually has, filtered down
    # to MASTER_ORDER members, must appear in the same order MASTER_ORDER declares.
    master_index = {p: i for i, p in enumerate(MASTER_ORDER)}
    seen_indices: list[int] = []
    unknown = [p for p in procs if p not in master_index]
    if unknown:
        err(f"pipeline {name!r}: processor(s) not in MASTER_ORDER: {unknown} "
            f"— add them to MASTER_ORDER in scripts/validate-collector-pipeline.py")
    for p in procs:
        if p in master_index:
            seen_indices.append(master_index[p])
    if seen_indices != sorted(seen_indices):
        err(f"pipeline {name!r}: processors are out of MASTER_ORDER relative order: "
            f"{[p for p in procs if p in master_index]}")

    # No processor may appear twice in one pipeline.
    dupes = {p for p in procs if procs.count(p) > 1}
    if dupes:
        err(f"pipeline {name!r}: processor(s) listed more than once: {sorted(dupes)}")


def check_signal_restriction(pipelines: dict[str, list[str]]) -> None:
    for proc, only_pipeline in SIGNAL_ONLY.items():
        for name, procs in pipelines.items():
            if proc in procs and name != only_pipeline:
                err(f"{proc!r} is restricted to the {only_pipeline!r} pipeline but "
                    f"also appears in {name!r}")


def main() -> int:
    if yaml is None:
        print("ERROR validate-collector-pipeline: PyYAML not installed "
              "(pip install pyyaml / uv run --with pyyaml)")
        return 1
    check_master_order_shape()
    pipelines = load_pipelines()
    if pipelines is not None:
        for name, procs in pipelines.items():
            check_pipeline(name, procs)
        check_signal_restriction(pipelines)

    for w in warnings:
        print(f"WARN  {w}")
    for e in errors:
        print(f"ERROR {e}")
    print()
    print(f"validate-collector-pipeline: {len(errors)} error(s), {len(warnings)} warning(s)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
