# SPEC-TW-021 — API Contract

Event-driven, no public HTTP endpoint.

Consumes: `tool.execution.result-unknown.v1`.

Outcomes: `RECORDED_UNKNOWN`, `DUPLICATE`, `STALE`, `CONFLICT_REQUIRES_RECONCILIATION`, `DLQ_SCHEMA_INVALID`, `DLQ_WRONG_PRODUCER`.
