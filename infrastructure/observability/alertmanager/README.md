# `alertmanager/` — alert routing, deduplication, silences

> Owner: `platform-observability`
> Filled by: `SPEC-OP-021` (routing/dedup/silence); consumes alert rules from `../rules/alerting/`

## Layout

```text
alertmanager/
├── base/
│   └── alertmanager.yml       # route tree, grouping, inhibition, receivers  (SPEC-OP-021)
└── overlays/{local,ci,production}/   # receiver endpoints, repeat intervals
```

## Rules for files added here

- No receiver may call a business write API or auto-remediate a domain
  ([forbidden-business-writes](../docs/forbidden-business-writes.md) F1). Receivers are
  paging / chat / incident-tool notifications only.
- `base/` owns the routing tree, grouping keys, and inhibition rules; overlays set
  only receiver endpoints (from the environment secret store, by reference),
  `group_wait` / `group_interval` / `repeat_interval`.
- Environment muting is done with routes / silences, not by forking rules.
- Silences created at runtime are a control-plane action and are audited
  ([ADR-0005](../docs/adr/0005-thin-control-plane-api-only-when-gitops-insufficient.md)).
- Merged config must pass `amtool check-config` in CI.

Image + tag pinned in [`../versions.env`](../versions.env) (`ALERTMANAGER_*`).
