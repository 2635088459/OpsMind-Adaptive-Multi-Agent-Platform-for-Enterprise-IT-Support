# SPEC-TW-023 — API Contract

Event-driven, no public HTTP endpoint.

Consumes: `verification.completed.v1` with `result = SUCCESS`.

Outcomes: `APPLIED`, `DUPLICATE`, `STALE`, `CONFLICT_REQUIRES_RECONCILIATION`, `DLQ_SCHEMA_INVALID`.
