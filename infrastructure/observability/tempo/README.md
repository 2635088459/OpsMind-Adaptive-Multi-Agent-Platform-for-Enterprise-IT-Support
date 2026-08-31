# `tempo/` — trace backend configuration

> Owner: `platform-observability`
> Filled by: `SPEC-OP-014` (backend), `SPEC-OP-015` (retention/sizing)
> Backend choice: [ADR-0002](../docs/adr/0002-prometheus-loki-tempo-backends.md)

## Layout

```text
tempo/
├── base/
│   └── tempo.yml              # distributor, ingester, storage, metrics_generator  (SPEC-OP-014)
└── overlays/{local,ci,production}/
```

## Rules for files added here

- `base/` defines block format, `metrics_generator` (span metrics / service graphs),
  and query limits; overlays set only storage backend (local filesystem vs. object
  storage), retention (`block_retention`), compaction cadence, resource limits.
- Tempo receives traces **only** from the Collector over internal OTLP `4317`; the
  host port is not exposed to producers.
- Span-metrics emitted by `metrics_generator` go to Prometheus; they are views, not
  business facts ([ADR-0004](../docs/adr/0004-observability-never-mutates-business-state.md)).
- Merged config must pass `tempo -config.check` in CI.

Image + tag pinned in [`../versions.env`](../versions.env) (`TEMPO_*`).
