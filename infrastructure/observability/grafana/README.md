# `grafana/` — dashboards, datasources, provisioning

> Owner: `platform-observability`
> Filled by: `SPEC-OP-016`–`SPEC-OP-019` (dashboards), `SPEC-OP-030` (access control)
> Dashboard JSON source of truth: [`../dashboards/`](../dashboards/)

## Layout

```text
grafana/
├── base/
│   └── provisioning/
│       ├── datasources/       # Prometheus, Loki, Tempo — QUERY ONLY
│       └── dashboards/        # provider that loads ../dashboards/*.json
└── overlays/{local,ci,production}/   # grafana.ini fragments, datasource URLs, auth
```

## Rules for files added here

- Datasources are provisioned **query-only**. No datasource holds a write-capable
  credential ([forbidden-business-writes](../docs/forbidden-business-writes.md) F3).
- Dashboards are the source of truth in [`../dashboards/`](../dashboards/); this
  directory only provisions them. Console edits are not authoritative
  ([ADR-0003](../docs/adr/0003-gitops-versioned-config-with-overlays.md)).
- Overlays set only: `grafana.ini` fragments, datasource URLs, auth mode (OIDC in
  production via domain-01), org/folder permissions (`SPEC-OP-030`).
- Grafana being down has no business impact and does not affect alerting
  ([component matrix §5](../docs/component-responsibility-matrix.md)).

Image + tag pinned in [`../versions.env`](../versions.env) (`GRAFANA_*`).
