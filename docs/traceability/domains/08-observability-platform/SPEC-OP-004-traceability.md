# SPEC-OP-004 Traceability — Resource Attribute Convention

> Domain: `08-observability-platform`
> Phase: `phase-01-unified-signal-contracts`
> Status: implemented
> Verified: 2026-08-30 (validators pass; smoke proves preservation + violation stamping; stack torn down)
> Owner: `platform-observability`

## 1. Objective mapping

Spec objective: *Standardize service, version, namespace, environment, instance, SDK,
tenant, and cloud/Kubernetes attributes.*

| Spec area | Where |
|---|---|
| service / version / namespace / environment / instance / SDK attributes | `signals/resource-attributes.md` §2 (required) + `signals/resource-attributes.yaml` `attributes[]` with `value_pattern` / `value_enum_ref` / `semconv` / `cardinality` |
| tenant attribute | `signals/resource-attributes.md` §4 + yaml `opsmind.tenant.id` (optional; single-tenant today; low-cardinality slug; governed by SPEC-OP-031) |
| cloud / Kubernetes attributes | `signals/resource-attributes.md` §4 + yaml `k8s.*` / `cloud.*` / `container.*` (optional; production overlay, SPEC-OP-008) |
| `service.namespace` allowed values | yaml `namespaces` = the 8 domain slugs + `shared` |
| `deployment.environment` allowed values | yaml `environments` = `local/ci/staging/production` |
| cardinality guidance | §6: replica-scoped keys stay on the Resource, never a Prometheus label |
| machine-readable + schema | `signals/resource-attributes.yaml` + `schemas/resource-attributes.schema.json` |
| governance alignment | `governance/telemetry-governance.yaml` `allow_fields.resource.required` refined to the 6-key set (v1.1.0); `validate-signal-contracts.py` fails if the two diverge |
| enforcement | Collector `resourcedetection` (env, system) fills host/os; `transform/resource-contract` sets `service.name=unknown_service` + `opsmind.resource.violation` when `service.name` is missing/blank — never drops (ADR-0004) |
| "schema plus signal-contract tests against Java and Python fixtures" | `signals/fixtures/resource-attributes/` — `conformant-java.json`, `conformant-python.json` (expect pass), `nonconformant-missing-service-name.json`, `nonconformant-bad-namespace-and-version.json` (expect reject); checked by `validate-signal-contracts.py` |
| alert + runbook (owner + version) | `rules/alerting/signal-conformance.yml` → `ResourceAttributeViolation`; `runbooks/ResourceAttributeViolation.md` |
| CI gate | `.github/workflows/observability-platform-ci.yml` `layout` job runs `validate-signal-contracts.py` + self-tests; `config` job `promtool check`s the new rule; `smoke` job asserts preservation + violation stamping |
| Traceability | this file + `traceability-entry.yaml` |

Deferred: producer SDK instrumentation in the services (domain teams / SPEC-OP-025+
cross-domain contracts); K8s/cloud detector config (production overlay, SPEC-OP-008);
W3C context propagation (SPEC-OP-005); promoting resource attrs to metric labels
safely (SPEC-OP-006); tenant isolation semantics (SPEC-OP-031).

## 2. Files added / changed

```text
infrastructure/observability/
  signals/resource-attributes.md                                   NEW
  signals/resource-attributes.yaml                                 NEW
  signals/fixtures/resource-attributes/conformant-java.json        NEW
  signals/fixtures/resource-attributes/conformant-python.json      NEW
  signals/fixtures/resource-attributes/nonconformant-missing-service-name.json        NEW
  signals/fixtures/resource-attributes/nonconformant-bad-namespace-and-version.json   NEW
  schemas/resource-attributes.schema.json                          NEW
  rules/alerting/signal-conformance.yml                            NEW
  runbooks/ResourceAttributeViolation.md                           NEW
  collector/base/config.yaml                                       CHANGED  (resourcedetection + transform/resource-contract + pipeline wiring)
  governance/telemetry-governance.yaml                             CHANGED  (v1.1.0: allow_fields.resource.required = 6-key set)

scripts/validate-signal-contracts.py                               NEW
scripts/tests/test_validate_signal_contracts.py                    NEW
scripts/observability-stack.sh                                     CHANGED  (smoke: conformant resource + missing-service.name trace + assertions)
.github/workflows/observability-platform-ci.yml                    CHANGED  (signal-contracts validation + rule check)

docs/specs/domains/08-observability-platform/SPEC-OP-004-.../traceability-entry.yaml  CHANGED
docs/traceability/domains/08-observability-platform/SPEC-OP-004-traceability.md       NEW (this file)
```

## 3. Commands run and results (2026-08-30 UTC)

| Command | Result |
|---|---|
| `python scripts/validate-observability-layout.py` | 0 errors (3 warnings: `audit_ref` for this file — cleared on commit) |
| `uv run --with pyyaml python scripts/validate-telemetry-governance.py` | 0 errors, 0 warnings (governance v1.1.0 still consistent) |
| `uv run --with pyyaml python scripts/validate-signal-contracts.py` | 0 errors, 0 warnings — contract shape OK; required set == governance required set; 2 conformant fixtures pass, 2 non-conformant fixtures rejected; Collector wired with `resourcedetection` + `transform/resource-contract` in all 3 pipelines |
| `uv run --with pyyaml python -m unittest discover -s scripts/tests` | **20 passed** (8 layout + 7 governance + 5 signal-contracts: conformant-helper, real tree, broken-fixture → fail, required-set-desync → fail, collector-unwired → fail) |
| `docker run … otelcol-contrib:0.116.1 validate --config=base --config=local` | exit 0 (OTTL `set(...) where … == nil` parses; `resourcedetection [env, system]` valid) |
| `promtool check rules …/signal-conformance.yml` | SUCCESS — 1 rule |
| `scripts/observability-stack.sh smoke` | **SMOKE: PASS** — conformant trace: Resource in Tempo = `service.name/version/namespace/instance.id`, `deployment.environment`, `telemetry.sdk.language`, `telemetry.pipeline`, plus **auto-detected `host.name` + `os.type`**. Violation trace (no `service.name`): still ingested, Resource = `service.name=unknown_service`, `opsmind.resource.violation=missing:service.name`, `host.name=79cd9f30e5d2`. Governance deny-list + metrics/logs/rules paths still green. |
| `scripts/observability-stack.sh down` | stack + volumes removed, 0 containers |

## 4. Residual risks

| Risk | Severity | Mitigation / owner |
|---|---|---|
| `transform/resource-contract` only auto-corrects **missing `service.name`**; bad `service.namespace` / `service.version` / non-canonical `deployment.environment` are not corrected | Medium | Surfaced by the contract-test on fixtures in CI and, at runtime, as unfamiliar label values; SPEC-OP-025+ cross-domain contracts hold each producer to the fixtures |
| `resourcedetection` uses `[env, system]` only — no `process` / `docker` / `k8s` / cloud detectors | Low | `process` detector rejected the per-attribute config on 0.116.1 and is container-flaky; K8s/cloud detectors are a production-overlay concern (SPEC-OP-008) |
| Governance `required` set and `resource-attributes.yaml` `required` set are kept in sync by a **set-equality** check, not one source | Low | `validate-signal-contracts.py` fails CI on divergence; `resource-attributes.yaml` is documented as authoritative for value patterns |
| `ResourceAttributeViolation` alert depends on `opsmind_resource_violation` reaching Prometheus as a label via `resource_to_telemetry_conversion` | Low | verified in smoke that the attribute is on the Resource; label conversion is already enabled on the `prometheus` exporter (SPEC-OP-002); SPEC-OP-006 will confirm the label form |
| No Java/Python **SDK-side** conformance test yet — fixtures are hand-authored golden payloads | Medium | `SPEC-OP-025`+ bind each service's real OTLP output to these fixtures; `SPEC-OP-004` scope is the contract + boundary enforcement |
| `service.instance.id` pattern is permissive (`uuid` or `host/pid`) | Low | tightened if a producer emits something unexpected; it is never a metric label |

## 5. Sign-off

The resource-attribute contract is defined (human + machine + schema), aligned with the
governance rulebook, enforced at the Collector boundary, covered by Java/Python golden
fixtures and negative fixtures, and proven at runtime: a conformant Resource reaches
Tempo intact (plus auto-detected host/os), and a Resource missing `service.name` is
ingested with `unknown_service` + `opsmind.resource.violation` stamped rather than
dropped. `SPEC-OP-005` (HTTP/AMQP W3C trace propagation) is next in phase-01.
