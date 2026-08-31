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
