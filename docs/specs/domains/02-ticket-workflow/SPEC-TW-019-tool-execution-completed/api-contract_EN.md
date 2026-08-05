# SPEC-TW-019 — API Contract

Event-driven, no public HTTP endpoint.

Consumes:

```text
tool.execution.completed.v1
```

Outcomes:

```text
APPLIED
DUPLICATE
STALE
REJECTED_BUSINESS_RULE
DLQ_SCHEMA_INVALID
DLQ_WRONG_PRODUCER
```
