# SPEC-TW-016 — API Contract

Event-driven, no public HTTP endpoint.

Consumes: `approval.rejected.v1`.

Outcomes: `APPLIED`, `DUPLICATE`, `STALE`, `REJECTED_BUSINESS_RULE`, `DLQ_SCHEMA_INVALID`, `DLQ_WRONG_PRODUCER`.
