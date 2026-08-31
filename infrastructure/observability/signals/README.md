# `signals/` — producer signal contracts

> Owner: `platform-observability` (transport) + source-domain teams (semantics)
> Filled by: `SPEC-OP-004` (resource attributes), `SPEC-OP-005` (HTTP/AMQP trace propagation), `SPEC-OP-006` (metric naming + cardinality), `SPEC-OP-007` (structured log + redaction)

## Purpose

The contract every OpsMind producer (Java + Python) must satisfy before its telemetry
is accepted at the Collector. Domain 08 owns the contract and validates conformance;
it does not edit service source ([platform-boundaries §3](../docs/platform-boundaries.md)).

The producer contracts here implement the **Telemetry Governance Baseline**
([`../governance/telemetry-governance.yaml`](../governance/telemetry-governance.yaml),
`SPEC-OP-003`): `allow_fields` becomes the per-signal required/recommended attribute
set (SPEC-OP-004), `deny_fields` is enforced at the Collector boundary, and
`cardinality_budgets` bounds metric labels (SPEC-OP-006).

## Layout (added by SPEC-OP-004+)

```text
signals/
├── resource-attributes.md      # service.name/version, deployment.environment, ...  (SPEC-OP-004)
├── trace-propagation.md        # W3C traceparent/tracestate over HTTP + AMQP        (SPEC-OP-005)
├── metric-naming.md            # naming + unit + cardinality budget                 (SPEC-OP-006)
├── structured-log.md           # log shape + redaction contract                    (SPEC-OP-007)
└── fixtures/                   # Java + Python conformance fixtures / golden samples
```

## Rules for files added here

- Each contract file carries the markdown metadata header
  ([artifact-metadata-convention §3](../docs/artifact-metadata-convention.md)).
- Every signal carries `service.name`, `service.version`, `deployment.environment`,
  timestamp, and trace / correlation linkage where applicable.
- Schema changes are additive or versioned — never a silent breaking change.
- Fixtures contain **no** secrets, tokens, raw prompts, raw user text, or unredacted
  PII ([forbidden-business-writes](../docs/forbidden-business-writes.md) F7). Redacted
  and synthetic values only.
- Cardinality: user / ticket / workflow IDs go in trace context / exemplars, never as
  metric labels or log stream labels (F8).
