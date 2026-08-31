# `loki/` — log backend configuration

> Owner: `platform-observability`
> Filled by: `SPEC-OP-013` (backend), `SPEC-OP-015` (retention/compaction/sizing)
> Backend choice: [ADR-0002](../docs/adr/0002-prometheus-loki-tempo-backends.md)

## Layout

```text
loki/
├── base/
│   └── loki.yml               # server, schema_config, limits_config, compactor  (SPEC-OP-013)
└── overlays/{local,ci,production}/
```

## Rules for files added here

- `base/` defines the schema, ingestion limits, and compaction; overlays set only
  storage (filesystem vs. object storage), retention period, per-tenant rate/stream
  limits, resource limits.
- Loki receives logs **only** from the Collector (port `3100` push). No direct
  producer traffic.
- Structured log shape and redaction are enforced upstream in the Collector per
  `SPEC-OP-007`; Loki must never persist unredacted PII / secrets
  ([forbidden-business-writes](../docs/forbidden-business-writes.md) F7).
- Keep label sets low-cardinality — no request / ticket / user IDs as stream labels.
- Merged config must pass `loki -verify-config` in CI.

Image + tag pinned in [`../versions.env`](../versions.env) (`LOKI_*`).
