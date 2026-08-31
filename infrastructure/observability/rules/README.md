# `rules/` — promoted recording and alert rule catalog

> Owner: `platform-observability`
> Filled by: `SPEC-OP-020` (rule catalog), `SPEC-OP-022`/`SPEC-OP-023` (SLO / burn-rate alerts)
> Loaded by: `../prometheus/base/prometheus.yml` `rule_files:`

```text
rules/
├── recording/                # <area>.yml  — derived series
└── alerting/                 # <area>.yml  — alert conditions incl. multi-window burn-rate
```

## Rules for files added here

- One file per area (`http-server.yml`, `amqp.yml`, `slo-burn-rate.yml`, …).
- Every file begins with the `# meta.*` header
  ([artifact-metadata-convention §3](../docs/artifact-metadata-convention.md)):
  `owner`, `version` (SemVer), `spec`, `access_policy`, `retention`, `runbook`,
  `rollback`, `audit_ref`.
- Every alert rule sets `labels.severity`, `labels.owner`, and annotations
  `summary`, `description`, `runbook_url` (→ `../runbooks/<alertname>.md`),
  `dashboard`, and a correlation query entry point
  ([signal/event contract](../../../docs/specs/domains/08-observability-platform/SPEC-OP-001-platform-boundaries-and-repository-layout/event-contract_EN.md)).
- Rules emit series and notifications only — never a domain event
  ([forbidden-business-writes](../docs/forbidden-business-writes.md) F2).
- `promtool check rules` must pass in CI; `promtool test rules` fixtures live beside
  the rule file where practical.
