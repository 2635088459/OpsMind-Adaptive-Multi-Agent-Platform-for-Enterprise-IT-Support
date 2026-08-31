# `runbooks/` — operational runbooks

> Owner: `platform-observability`
> Filled by: `SPEC-OP-024` (runbook catalog); referenced by `../rules/alerting/*.yml` `runbook_url`

## Rules for files added here

- One file per alert class, named after the `alertname`: `runbooks/<AlertName>.md`.
- Markdown blockquote metadata header
  ([artifact-metadata-convention §3](../docs/artifact-metadata-convention.md)):
  `owner`, `version`, `spec`, `access_policy`, `retention`, `runbook: self`,
  `rollback`, `audit_ref`.
- Body sections: **Impact**, **Detection** (the firing expression + dashboard link),
  **Triage**, **Mitigation**, **Resolution**, **Rollback**, **Escalation**,
  **Post-incident**.
- Mitigation steps that touch a business domain must route through that domain's
  policy/approval (domain-06) and tool gateway (domain-05) — a runbook here never
  instructs a direct write from the observability side
  ([forbidden-business-writes §4](../docs/forbidden-business-writes.md)).

## Template

See [`TEMPLATE.md`](TEMPLATE.md).
