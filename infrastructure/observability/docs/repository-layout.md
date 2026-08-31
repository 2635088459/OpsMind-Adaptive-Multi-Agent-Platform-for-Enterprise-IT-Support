# Repository Layout Contract

> Spec: `SPEC-OP-001`
> Owner: `platform-observability`
> Status: authoritative — checked by `scripts/validate-observability-layout.py`

Every Observability Platform spec adds files under a predictable path. This contract
fixes those paths so reviewers, CI, and later specs never guess.

## 1. Top-level tree

| Path | Contents | Filled by |
|---|---|---|
| `docs/` | Governance docs + ADRs | `SPEC-OP-001` (this spec) |
| `governance/` | `telemetry-governance.yaml` — deny/allow fields, retention classes, cardinality budgets, schema-review, exception waivers | `SPEC-OP-003` |
| `schemas/` | JSON Schemas for metadata and contract validation | `SPEC-OP-001`+ |
| `signals/` | Resource-attribute, propagation, metric-naming, log/redaction contracts + producer fixtures | `SPEC-OP-004` … `SPEC-OP-007` |
| `collector/` | OTel Collector configuration | `SPEC-OP-002`, `SPEC-OP-008` … `SPEC-OP-011` |
| `prometheus/` | Prometheus server config + scrape config | `SPEC-OP-012`, `SPEC-OP-015` |
| `loki/` | Loki config | `SPEC-OP-013`, `SPEC-OP-015` |
| `tempo/` | Tempo config | `SPEC-OP-014`, `SPEC-OP-015` |
| `grafana/` | Datasource + dashboard provisioning, org/permission model | `SPEC-OP-016` … `SPEC-OP-019`, `SPEC-OP-030` |
| `alertmanager/` | Routing, grouping, inhibition, receivers | `SPEC-OP-021` |
| `rules/recording/` | Promoted recording rules | `SPEC-OP-020`, `SPEC-OP-022` |
| `rules/alerting/` | Promoted alert rules incl. burn-rate | `SPEC-OP-020`, `SPEC-OP-023` |
| `dashboards/` | Dashboard JSON source of truth | `SPEC-OP-016` … `SPEC-OP-019` |
| `runbooks/` | One runbook per alert class | `SPEC-OP-024` |

## 2. `base/` + `overlays/` per component

Each of `collector/`, `prometheus/`, `loki/`, `tempo/`, `grafana/`, `alertmanager/`
follows the same shape:

```text
<component>/
├── README.md                    # what lives here, owning spec, required metadata
├── base/                        # environment-independent config
└── overlays/
    ├── local/                   # docker compose dev (SPEC-OP-002)
    ├── ci/                      # ephemeral CI / Testcontainers topology
    └── production/              # documented production topology
```

- `base/` holds the full, valid configuration with no environment-specific values.
- Each overlay holds **only** the diff for that environment: endpoints, resource
  limits, retention, replica count, auth mode, storage class.
- An overlay never redefines pipeline logic, rule content, or dashboard structure —
  only environment parameters. See
  [`environment-overlays.md`](environment-overlays.md).
- No overlay may weaken a redaction, sampling-floor, or cardinality control defined in
  `base/`.

## 3. Naming

| Kind | Pattern | Example |
|---|---|---|
| ADR | `docs/adr/NNNN-kebab-title.md` | `docs/adr/0001-otel-collector-sole-ingestion-boundary.md` |
| Dashboard | `dashboards/<area>-<name>.json` | `dashboards/golden-path-service-overview.json` |
| Recording rule file | `rules/recording/<area>.yml` | `rules/recording/http-server.yml` |
| Alert rule file | `rules/alerting/<area>.yml` | `rules/alerting/slo-burn-rate.yml` |
| Runbook | `runbooks/<alertname>.md` | `runbooks/HighRequestErrorRate.md` |
| Signal contract | `signals/<topic>.md` (+ `signals/fixtures/…`) | `signals/resource-attributes.md` |

## 4. Required metadata header

Every dashboard, rule file, runbook, and signal contract begins with a metadata block
conforming to [`../schemas/artifact-metadata.schema.json`](../schemas/artifact-metadata.schema.json)
and documented in [`artifact-metadata-convention.md`](artifact-metadata-convention.md).
Config files under `base/`/`overlays/` carry a lighter header: `# owner:`, `# spec:`,
`# rollback:`.

## 5. What must never appear

- Secrets, tokens, passwords, `Authorization` headers, MFA material, private keys.
- Raw prompts, raw user text, unredacted PII in fixtures or sample payloads.
- Unpinned image references (`:latest`, tag without digest in `versions.env`).
- Exporters / receivers / receivers targeting a business system.
- Business data of any kind.

`scripts/validate-observability-layout.py` enforces sections 1–5 and fails CI on a
violation.
