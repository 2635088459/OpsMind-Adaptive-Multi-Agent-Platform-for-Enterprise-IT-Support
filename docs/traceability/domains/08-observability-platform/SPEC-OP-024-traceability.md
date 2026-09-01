# SPEC-OP-024 Traceability — Operational Runbook Catalog

> Domain: `08-observability-platform`
> Phase: `phase-05-alerts-slos-runbooks` (closes this phase)
> Status: implemented
> Verified: 2026-08-31
> Owner: `platform-observability`

## 1. Objective mapping

Concrete objective: *"Require every paging alert to link an owned runbook
with diagnosis, mitigation, recovery, and escalation."*

| Requirement | Where | Built when |
|---|---|---|
| Runbook has a real, versioned metadata header (owner, version, etc.) | `validate-observability-layout.py`'s governed-artifact header check | `SPEC-OP-001` |
| Alert's `runbook_url` resolves to a real file | `validate-rule-catalog.py::check_alerting_file` | `SPEC-OP-020` |
| Orphaned runbook (no alert points to it) flagged | `validate-rule-catalog.py::check_orphaned_runbooks` | `SPEC-OP-020` |
| **Runbook body actually HAS diagnosis/mitigation/recovery/escalation, filled in for real** | `validate-rule-catalog.py::check_runbook_structure` | **`SPEC-OP-024` (this spec — the one genuine gap)** |

## 2. Files added / changed

```text
scripts/validate-rule-catalog.py                CHANGED (new check_runbook_structure, _split_sections,
                                                          REQUIRED_RUNBOOK_SECTIONS, placeholder-text table)
scripts/tests/test_validate_rule_catalog.py      CHANGED (6 new tests; 3 existing call sites updated for
                                                          check_alerting_file's new severities param)

infrastructure/observability/
  runbooks/README.md                            CHANGED (corrected the stale "one file per alert" rule;
                                                          documented the structure-check enforcement)
  runbooks/TelemetryBackupRestore.md             CHANGED (real bug fix — see §4)
  rules/CATALOG.md                              CHANGED (documents the new structure check)

docs/specs/domains/08-observability-platform/SPEC-OP-024-.../traceability-entry.yaml  CHANGED
docs/traceability/domains/08-observability-platform/SPEC-OP-024-traceability.md       NEW (this file)
```

No collector/Prometheus/Loki/Tempo/Alertmanager/Grafana config changed — this
spec is pure validator + documentation, closing a gap in already-shipped
tooling rather than adding a new signal, rule, or alert.

## 3. Commands run and results (2026-08-31)

| Command | Result |
|---|---|
| `validate-rule-catalog.py` (before the `TelemetryBackupRestore.md` fix) | **1 error**: `runbooks/TelemetryBackupRestore.md: missing required '## Detection' section` — the new check's first real finding |
| `validate-rule-catalog.py` (after the fix) | 0 errors, 10 warnings — all pre-existing and already documented (naming-convention exceptions for `slo_burn_rate_ratio`/`slo_error_budget_ratio`, and the 2 deliberately-alert-less orphaned runbooks) |
| `validate-observability-layout.py` | 0 errors, 0 warnings |
| `validate-telemetry-governance.py` | 0 errors, 0 warnings |
| `validate-signal-contracts.py` | 0 errors, 0 warnings |
| `validate-collector-pipeline.py` | 0 errors, 0 warnings |
| `validate-dashboards.py` | 0 errors, 0 warnings |
| `python -m unittest discover -s scripts/tests` | **82 passed** (was 76; 6 new) |

## 4. Real bug found and fixed: an inconsistent runbook heading

Running the new structural check against the real repository for the first
time (before fixing anything) produced exactly one error:

```
ERROR runbooks/TelemetryBackupRestore.md: missing required '## Detection'
      section (referenced by severity=['critical', 'warning'] alert(s))
```

Every other alert-linked runbook in this domain (11 of them:
`HttpGoldenSignals`, `TargetDown`, `SloBurnRateAlerts`, `SloErrorBudget`,
`CollectorBackpressure`, `PrometheusTsdbCapacity`, `TempoIngestHealth`,
`ResourceAttributeViolation`, `MetricCardinalityBudget`,
`StructuredLogContractViolation`, and `TelemetryBackupRestore` itself for
every *other* section) already used the exact plain heading text
`## Detection`. `TelemetryBackupRestore.md` alone had written
`## Detection (retention/compaction health)` — real, substantial content
underneath, just a heading that didn't match the convention's exact text, so
the parser's exact-heading lookup missed it entirely.

**Why fix the file instead of loosening the check:** every other runbook in
the domain already follows the plain-heading convention exactly, with zero
exceptions. Making the parser tolerant of arbitrary heading suffixes would
accept future drift silently — the one outlier was cheap and correct to fix
directly (move the qualifier into the section body), keeping the convention
worth having.

**Why this is real evidence the check matters, not vacuous:** the fact that
*every other* runbook in the domain passed the moment the check was written
— with no other edits needed — proves the check is exercising a real,
already-almost-universally-followed convention rather than one invented to
make a fabricated finding.

## 5. Second correction: a stale, already-ignored README rule

`runbooks/README.md` stated: *"One file per alert class, named after the
`alertname`: `runbooks/<AlertName>.md`."* This was already false for every
multi-alert runbook shipped since `SPEC-OP-020` — `HttpGoldenSignals.md`
covers both `HighRequestErrorRate` and `HighRequestLatency`;
`SloBurnRateAlerts.md` covers all 4 burn-rate tiers; `TempoIngestHealth.md`
covers 3 Tempo alerts; and so on. The grouped-by-incident-class convention is
better (it avoids near-duplicate files for closely related alerts sharing one
on-call path) and was already the de facto standard — the README's stated
rule was simply never true and never enforced. Rewrote it to describe the
real convention, cross-referencing `rules/CATALOG.md` as the authoritative
alert→runbook map, rather than leave a rule on record that nothing follows.

## 6. Residual risks

| Risk | Severity | Mitigation / owner |
|---|---|---|
| The structural check verifies a section is present and non-placeholder, not that its content is *correct* (e.g. an Escalation section could name the wrong on-call rotation and still pass) | Low | content correctness is a human-review concern; the check's job is to prevent an empty/stub section from silently passing, which it now does |
| `severity` values other than `critical`/`warning` (if introduced later) fall through to the advisory (warning) branch by default | Low | acceptable — `critical` is this domain's only paging tier today (per `SPEC-OP-021`'s Alertmanager routing); a new tier would need its own routing decision first anyway |
| Two runbooks (`AlertRoutingAndSilencing.md`, `BrokenTracePropagation.md`) remain deliberately unreferenced by any alert, so this spec's structural check never runs against them | Low | both are pre-existing, already-documented deliberate exceptions (an operational guide and a "no clean single-series signal" design choice, respectively) — not new scope for this spec to revisit |

## 7. Sign-off

The one genuine gap in `SPEC-OP-024`'s objective — a runbook's *body content*,
not just its existence — is now real, CI-enforced tooling, asymmetrically
strict exactly where the objective's own wording demands it (paging/critical
alerts hard-fail; ticket-tier alerts get a warning). Verifying it against the
real repository surfaced and fixed one genuine, isolated heading
inconsistency and one stale, already-ignored README rule, rather than
inventing filler work to justify the spec. This closes phase-05
(`SPEC-OP-020` through `SPEC-OP-024`) of the observability-platform domain
roadmap in full.
