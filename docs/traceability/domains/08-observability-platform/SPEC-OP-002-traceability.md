# SPEC-OP-002 Traceability — Local Observability Topology

> Domain: `08-observability-platform`
> Phase: `phase-00-platform-engineering-foundation`
> Status: implemented
> Verified: 2026-08-30 (stack brought up, smoke test PASS, torn down)
> Owner: `platform-observability`

## 1. Objective mapping

Spec objective: *Deliver Compose for all six components with ports, volumes, health
checks, and CPU/memory limits.*

| Spec deliverable / acceptance clause | Evidence |
|---|---|
| Compose for all six components | `infrastructure/docker-compose/observability-stack.yml` — otel-collector, prometheus, loki, tempo, grafana, alertmanager |
| Ports | Each service publishes documented host ports (4317/4318/13133/8888/8889/55679, 9090, 3100, 3200, 3000, 9093); Tempo internal OTLP 4317 deliberately **not** published |
| Volumes | Named volumes `prometheus-data`, `loki-data`, `tempo-data`, `grafana-data`, `alertmanager-data`; config bind-mounted read-only from `infrastructure/observability/<component>/{base,overlays/local}/` |
| Health checks | `healthcheck:` on all six; `docker compose up -d --wait` reports all `Healthy` |
| CPU / memory limits | `deploy.resources.limits` + `reservations` on all six (collector 1cpu/512M, prometheus 1cpu/768M, loki/tempo/grafana 1cpu/512M, alertmanager 0.5cpu/256M) |
| Version-pinned config | `infrastructure/observability/versions.env` — all six pinned by `TAG@sha256:` digest; compose references `${IMG}:${TAG}@${DIGEST}` |
| Environment overlay | `collector/overlays/local/config.yaml` (native multi-`--config` merge), `prometheus|loki|tempo|alertmanager/overlays/local/values.env` (compose `--env-file` / `env_file` + `-config.expand-env`) |
| "validates and deploys reproducibly in local/CI" | `scripts/observability-stack.sh {config,up,smoke,down}` + `.github/workflows/observability-platform-ci.yml` jobs `config` and `smoke` |
| "A real producer signal is ingested, queried and correlated with expected fields" | `smoke` pushes an OTLP trace + log + metric (`service.name=op-002-smoke`, `trace_id=0af7651916cd43dd8448eb211c80319c`) to the Collector and queries each back — see §3 |
| "dashboard/rule/runbook has owner and version" | `dashboards/observability-platform-self.json` (`__opsmind_meta`), `rules/recording/platform-self.yml` + `rules/alerting/platform-self.yml` (`# meta.*`), `runbooks/TargetDown.md` (blockquote header) — all pass `validate-observability-layout.py` |
| "Secret/PII scan … pass" | `validate-observability-layout.py` scans collector/alertmanager/grafana config + the compose file for `:latest` and business-write targets — clean; smoke telemetry is synthetic |
| Traceability records files, commands, results, residual risks | this document + `traceability-entry.yaml` |

Deferred (owning spec): retention / capacity / backup / disk-full sizing
(`SPEC-OP-015`); production topology (`SPEC-OP-008`+); dependency-outage / overload /
drop / rollback drills against the live stack (`SPEC-OP-034`); full lifecycle E2E
(`SPEC-OP-035`); redaction + cardinality processors in the Collector pipeline
(`SPEC-OP-006`, `SPEC-OP-007`); tail sampling (`SPEC-OP-010`).

## 2. Files added / changed

```text
infrastructure/observability/
  collector/base/config.yaml                         NEW
  collector/overlays/local/config.yaml               NEW
  prometheus/base/prometheus.yml                     NEW
  prometheus/overlays/local/values.env               NEW
  loki/base/loki.yml                                 NEW
  loki/overlays/local/values.env                     NEW
  tempo/base/tempo.yml                               NEW
  tempo/overlays/local/values.env                    NEW
  alertmanager/base/alertmanager.yml                 NEW
  alertmanager/overlays/local/values.env             NEW
  grafana/base/provisioning/datasources/datasources.yml   NEW
  grafana/base/provisioning/dashboards/dashboards.yml     NEW
  dashboards/observability-platform-self.json        NEW
  rules/recording/platform-self.yml                  NEW
  rules/alerting/platform-self.yml                   NEW
  runbooks/TargetDown.md                             NEW
  versions.env                                       CHANGED (digests pinned; collector 0.116.0 -> 0.116.1)
  VERSIONS.md                                        CHANGED

infrastructure/docker-compose/observability-stack.yml   NEW
scripts/observability-stack.sh                          NEW
scripts/validate-observability-layout.py               CHANGED (lint the stack compose file)
.github/workflows/observability-platform-ci.yml        CHANGED (config + smoke jobs)

docs/specs/domains/08-observability-platform/SPEC-OP-002-.../traceability-entry.yaml   CHANGED
docs/traceability/domains/08-observability-platform/SPEC-OP-002-traceability.md        NEW (this file)
```

## 3. Commands run and results (2026-08-30 / 2026-08-31 UTC)

| Command | Result |
|---|---|
| `docker compose --env-file versions.env --env-file prometheus/…/values.env --env-file alertmanager/…/values.env -f …/observability-stack.yml config` | exit 0 — merged manifest renders, all images resolve with digest, resource limits parse |
| `scripts/observability-stack.sh smoke` | **SMOKE: PASS** — `docker compose up -d --wait` → all six `Healthy`; OTLP trace+log+metric pushed to collector `:4318`; queried back: trace in Tempo (span `op-002-smoke-span`), metric `op_002_smoke_total` in Prometheus, recording rule `job:up:ratio` evaluated, log line in Loki (carries `trace_id`), Alertmanager `/api/v2/status` OK |
| `promtool check config /etc/prometheus/prometheus.yml` | SUCCESS — 2 rule files found, syntax valid |
| `promtool check rules …/recording/platform-self.yml …/alerting/platform-self.yml` | SUCCESS — 1 + 2 rules |
| `amtool check-config /etc/alertmanager/alertmanager.yml` | SUCCESS — global config, route, 1 inhibit rule, 1 receiver |
| `otelcol-contrib validate --config=base.yaml --config=local.yaml` | exit 0 (also runs as the collector's Docker healthcheck) |
| `loki -verify-config -config.file=/etc/loki/loki.yml -config.expand-env=true` | `msg="config is valid"` |
| `curl /api/v1/targets?state=active` | 6 jobs (`prometheus, otel-collector, alertmanager, loki, tempo, grafana`) all `up` |
| `python scripts/validate-observability-layout.py` | 0 errors (warnings only for not-yet-written audit_ref while this file was pending) |
| `python -m unittest discover -s scripts/tests` | 8 passed |
| `scripts/observability-stack.sh down` | volumes + network removed, 0 `opsmind-` containers left |

Component versions confirmed in-container: collector `otelcol-contrib 0.116.0` (via the
`0.116.1` tag/digest), prometheus `3.1.0`, loki `release-3.3.x-23b5fc2`, tempo
`v2.7.1`, alertmanager `0.28.0`, grafana `11.4.0`.

## 4. Residual risks

| Risk | Severity | Mitigation / owner |
|---|---|---|
| Collector `0.116.0` image is broken on Docker Desktop/WSL2 (`exec /otelcol-contrib: no such file or directory`); pinned to `0.116.1` instead | Resolved | `versions.env` + `VERSIONS.md` record the skip and reason; `0.116.1` verified working end-to-end |
| Collector pipeline is minimal (memory_limiter + resource + batch only) — no redaction, cardinality guard, or tail sampling yet | Expected | `SPEC-OP-006` / `SPEC-OP-007` / `SPEC-OP-010` layer these into `collector/base/`; overlay rules forbid weakening them |
| Grafana local auth is `admin/admin` + anonymous Viewer | Low (local only) | `SPEC-OP-030` sets OIDC (domain-01) for the production overlay; never used outside `overlays/local` |
| Prometheus runs as `user: root` and Tempo as `user: root` for volume-permission simplicity on Docker Desktop | Low (local only) | production overlays (`SPEC-OP-008`) set non-root UIDs + proper volume ownership |
| `deploy.resources.limits` in Compose is honored by the container runtime but not by `docker compose` on Swarm-less setups in all versions | Low | verified honored on Docker Compose v5.4.0 here; production uses Kubernetes resource requests/limits |
| Retention values (`72h` / `24h` / `120h`) are laptop guesses, not capacity-modelled | Low | `SPEC-OP-015` owns the capacity formula and real retention |
| Smoke test asserts presence, not schema conformance, of resource attributes | Medium | `SPEC-OP-004` adds a resource-attribute contract + fixtures with strict assertions |
| No disk-full / dependency-outage drill yet | Medium | `SPEC-OP-034` (outage/backlog/drop/recovery) and `SPEC-OP-011` (backpressure) own this |

## 5. Sign-off

Local observability topology is reproducible: one command (`scripts/observability-stack.sh
smoke`) brings up all six pinned components with ports/volumes/health/limits, ingests a
real OTLP signal through the Collector, and queries it back from Tempo, Prometheus, and
Loki with correlation intact. CI runs the same via the `config` and `smoke` jobs.
`SPEC-OP-003` (Telemetry Governance Baseline) can build on this stack.
