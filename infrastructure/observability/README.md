# OpsMind Observability Platform (Domain 08)

> Owner: `platform-observability`
> Status: foundation established by `SPEC-OP-001`
> Stack: OpenTelemetry Collector, Prometheus, Loki, Tempo, Grafana, Alertmanager
> Delivery model: GitOps — every file here is version controlled, reviewed, and reproducible

This tree is the **single source of truth** for the OpsMind telemetry data plane and
operational control plane. It contains only observability configuration, contracts,
dashboards, rules, runbooks, and governance documents. It never contains business
logic, business data, or credentials.

## What `SPEC-OP-001` delivers

`SPEC-OP-001` does **not** ship working component configuration. It establishes the
boundaries and the repository contract that every later Observability Platform spec
(`SPEC-OP-002` … `SPEC-OP-036`) must satisfy:

1. Data-plane / control-plane ownership — [`docs/platform-boundaries.md`](docs/platform-boundaries.md)
2. Forbidden business writes — [`docs/forbidden-business-writes.md`](docs/forbidden-business-writes.md)
3. Component responsibility matrix — [`docs/component-responsibility-matrix.md`](docs/component-responsibility-matrix.md)
4. Architecture Decision Records — [`docs/adr/`](docs/adr/)
5. Repository layout contract — [`docs/repository-layout.md`](docs/repository-layout.md)
6. Version pinning — [`versions.env`](versions.env) / [`VERSIONS.md`](VERSIONS.md)
7. Environment overlay strategy — [`docs/environment-overlays.md`](docs/environment-overlays.md)
8. Artifact metadata convention — [`docs/artifact-metadata-convention.md`](docs/artifact-metadata-convention.md)

## Directory map

```text
infrastructure/observability/
├── README.md                       # this file
├── versions.env                    # pinned image tags + digests (machine readable)
├── VERSIONS.md                     # version rationale + upgrade policy
├── CODEOWNERS                      # review ownership for this tree
├── docs/                           # governance documents (SPEC-OP-001)
│   ├── platform-boundaries.md
│   ├── forbidden-business-writes.md
│   ├── component-responsibility-matrix.md
│   ├── repository-layout.md
│   ├── environment-overlays.md
│   ├── artifact-metadata-convention.md
│   ├── telemetry-governance.md     # SPEC-OP-003: policy + exception workflow
│   └── adr/                        # architecture decision records
├── governance/
│   └── telemetry-governance.yaml   # SPEC-OP-003: deny/allow fields, retention
│                                   #   classes, cardinality budgets, exceptions
├── schemas/
│   ├── artifact-metadata.schema.json
│   └── telemetry-governance.schema.json
├── collector/                      # OTel Collector config      (SPEC-OP-002, 008-011)
│   ├── base/
│   └── overlays/{local,ci,production}/
├── prometheus/                     # metrics backend + rules    (SPEC-OP-012, 020, 023)
│   ├── base/
│   └── overlays/{local,ci,production}/
├── loki/                           # log backend                (SPEC-OP-013, 015)
│   ├── base/
│   └── overlays/{local,ci,production}/
├── tempo/                          # trace backend              (SPEC-OP-014, 015)
│   ├── base/
│   └── overlays/{local,ci,production}/
├── grafana/                        # dashboards + provisioning  (SPEC-OP-016-019)
│   ├── base/
│   └── overlays/{local,ci,production}/
├── alertmanager/                   # alert routing              (SPEC-OP-021)
│   ├── base/
│   └── overlays/{local,ci,production}/
├── rules/                          # promoted rule catalog      (SPEC-OP-020, 023)
│   ├── recording/
│   └── alerting/
├── dashboards/                     # dashboard JSON source      (SPEC-OP-016-019)
├── runbooks/                       # operational runbooks       (SPEC-OP-024)
└── signals/                        # signal / resource-attribute contracts (SPEC-OP-004-007)
```

Each component directory carries its own `README.md` naming the spec that fills it and
the metadata every file must declare. Empty leaf directories are held by `.gitkeep`
until the owning spec lands.

## Non-negotiable rules (enforced by review and `scripts/validate-observability-layout.py`)

- Observability failure never blocks domains 01–07. Business availability outranks
  telemetry delivery.
- No file here mutates business state, and no alert action writes a domain fact.
- No secrets, tokens, `Authorization` headers, MFA material, raw prompts, raw user
  text, or unredacted PII — in config, fixtures, or committed telemetry samples.
- Metric labels stay low-cardinality; user / ticket / workflow IDs are never
  Prometheus labels.
- Every dashboard, rule, runbook, and signal contract declares `owner`, `version`,
  `access_policy`, `retention`, `runbook`, `rollback`, and `audit_ref`.
- Component images are pinned by tag **and** digest in [`versions.env`](versions.env).

See [`docs/repository-layout.md`](docs/repository-layout.md) for the full contract and
[中文设计](../../docs/low-level-design/domains/08-observability-platform/README_CN.md) for the domain design.
