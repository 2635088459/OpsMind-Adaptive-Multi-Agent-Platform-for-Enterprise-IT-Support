# `prometheus/` — metrics backend configuration

> Owner: `platform-observability`
> Filled by: `SPEC-OP-012` (backend), `SPEC-OP-015` (retention/compaction/sizing); consumes rules from `../rules/`
> Backend choice: [ADR-0002](../docs/adr/0002-prometheus-loki-tempo-backends.md)

## Layout

```text
prometheus/
├── base/
│   ├── prometheus.yml         # global, scrape_configs, rule_files, alerting  (SPEC-OP-012)
│   └── web.yml                # (optional) web / TLS / auth                    (SPEC-OP-030)
└── overlays/{local,ci,production}/
```

## Rules for files added here

- `rule_files:` in `base/prometheus.yml` point at `../rules/recording/*.yml` and
  `../rules/alerting/*.yml` — rules are **not** authored inside overlays.
- Overlays set only: `--storage.tsdb.retention.*`, external labels, remote-write
  targets, scrape targets that differ per environment, resource limits.
- Never add a user / ticket / workflow ID as a label — cardinality budget
  (`SPEC-OP-006`), [forbidden-business-writes](../docs/forbidden-business-writes.md) F8.
- Prometheus is not a system of record ([ADR-0004](../docs/adr/0004-observability-never-mutates-business-state.md)).
- Merged config must pass `promtool check config` and `promtool check rules` in CI.

Image + tag pinned in [`../versions.env`](../versions.env) (`PROMETHEUS_*`).
