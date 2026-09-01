# `runbooks/` — operational runbooks

> Owner: `platform-observability`
> Filled by: `SPEC-OP-024` (runbook catalog); referenced by `../rules/alerting/*.yml` `runbook_url`

## Rules for files added here

- One file per **incident class**, named after its theme (e.g. `HttpGoldenSignals.md`,
  `SloBurnRateAlerts.md`), not necessarily 1:1 with a single `alertname` — several
  related alerts sharing one root cause and one on-call path share one runbook
  (see `../rules/CATALOG.md` for the real alert→runbook mapping). Splitting every
  `alertname` into its own near-duplicate file was tried in practice and abandoned;
  document that choice here instead of a rule nothing actually follows.
- Markdown blockquote metadata header
  ([artifact-metadata-convention §3](../docs/artifact-metadata-convention.md)):
  `owner`, `version`, `spec`, `access_policy`, `retention`, `runbook: self`,
  `rollback`, `audit_ref`.
- Body sections: **Impact**, **Detection** (the firing expression + dashboard link),
  **Triage**, **Mitigation**, **Resolution**, **Rollback**, **Escalation**,
  **Post-incident** — headings exactly as spelled here (no per-runbook suffixes;
  put extra context in the section body instead). `scripts/validate-rule-catalog.py`
  (`SPEC-OP-024`) enforces this for every runbook an alert's `runbook_url` points
  to: an error if it backs at least one `severity: critical` ("paging") alert, a
  warning otherwise — checking the section is both present AND has real content,
  not the literal `TEMPLATE.md` boilerplate left unfilled.
- Mitigation steps that touch a business domain must route through that domain's
  policy/approval (domain-06) and tool gateway (domain-05) — a runbook here never
  instructs a direct write from the observability side
  ([forbidden-business-writes §4](../docs/forbidden-business-writes.md)).

## Template

See [`TEMPLATE.md`](TEMPLATE.md).
