# Version Pinning and Upgrade Policy

> Spec: `SPEC-OP-001`
> Owner: `platform-observability`
> Machine-readable pins: [`versions.env`](versions.env)

## 1. Why pin

Reproducibility across local / CI / production, and a controlled blast radius for
upgrades. An unpinned `:latest` makes "it worked yesterday" unprovable and lets a
component change under us.

## 2. What is pinned

| Component | Image | Tag | Notes |
|---|---|---|---|
| OTel Collector | `otel/opentelemetry-collector-contrib` | `0.116.1` | contrib distribution — needs `transform`/`filter`/`redaction`/`tail_sampling` processors. `0.116.0` skipped: its published image failed `exec /otelcol-contrib` on Docker Desktop/WSL2 during SPEC-OP-002 bring-up; `0.116.1` is the patch release and runs clean. |
| Prometheus | `prom/prometheus` | `v3.1.0` | PromQL, native histograms, TSDB |
| Loki | `grafana/loki` | `3.3.2` | single-binary for local; scalable target for prod |
| Tempo | `grafana/tempo` | `2.7.1` | object-store blocks, metrics-generator |
| Grafana | `grafana/grafana` | `11.4.0` | provisioning + Explore correlation |
| Alertmanager | `prom/alertmanager` | `v0.28.0` | routing / dedup / silence |

Digests are pinned in `versions.env` (recorded from `docker inspect` after the
`SPEC-OP-002` first pull and local bring-up, 2026-08-30). All six are real
`sha256:` values. The literal `PENDING-SPEC-OP-002` is still accepted by the
validator (warn, not fail) for any future component not yet brought up.

### SPEC-OP-036 update — components added since this table was first written

The 6 above are the real telemetry backends `versions.env`'s own pin
discipline governs (per `ADR-0003`). 3 more real, running components were
added by later specs and are **not** in `versions.env` — a deliberate,
stated distinction, not an oversight:

| Component | Image | Tag | Spec | Why not in `versions.env` |
|---|---|---|---|---|
| Postgres (dedicated, throwaway) | `postgres` | `16-alpine` | `SPEC-OP-029` | exists purely so `postgres_exporter` has something real to scrape; not this domain's own telemetry backend |
| postgres_exporter | `quay.io/prometheuscommunity/postgres-exporter` | `v0.15.0` | `SPEC-OP-029` | an infra-metrics sidecar, same reasoning |
| RabbitMQ (dedicated, throwaway) | `rabbitmq` | `4.3.4-management` | `SPEC-OP-029` | same reasoning; exposes metrics via its own built-in `rabbitmq_prometheus` plugin, no separate exporter |
| synthetic-probe | locally built (`python:3.12-slim` base, not digest-pinned) | n/a | `SPEC-OP-033` | a local utility sidecar this domain builds and owns, not a third-party telemetry backend `ADR-0003`'s digest-pin discipline targets — a real, stated asymmetry (see that spec's own traceability doc) |

All 3 external images above (`postgres`, `postgres_exporter`, `rabbitmq`) ARE
digest-pinned, in `versions.env`, under the same discipline as the original
6 — they are simply a different CATEGORY (infra-metrics sidecars, not this
domain's own telemetry backends) from the table above, which is why they
were not originally listed in it. Only `synthetic-probe` is genuinely
unpinned by digest, a real, accepted asymmetry stated in `SPEC-OP-033`'s
traceability doc, not fixed here since it does not affect release
readiness (a local build artifact, not a third-party supply-chain
dependency).

## 3. Upgrade policy

1. One component per PR. Bump `<COMPONENT>_TAG` **and** `<COMPONENT>_DIGEST` together.
2. Read the component's changelog; note breaking changes in the PR description.
3. CI must pass: layout validator + that component's native config check + (once
   `SPEC-OP-002` lands) the local topology smoke test.
4. `platform-observability` review required. Collector and Prometheus upgrades also
   need a note on processor / rule / cardinality impact.
5. Roll out `local` → `ci` → `production` via overlays; never skip an environment.
6. Rollback = `git revert` of the version PR + redeploy. Telemetry data written by the
   newer version remains readable by the older one for these components within one
   minor; if not, the PR must say so.

## 4. Compatibility anchors

- OTLP is the producer contract (ADR-0001); Collector upgrades must keep OTLP
  `4317`/`4318` receiver behavior stable for producers.
- Prometheus remote-write / scrape and PromQL used by `rules/` and `dashboards/`.
- Grafana schema version for provisioned dashboards in `dashboards/`.
- Tempo block format vs. retention window in `SPEC-OP-015`.
