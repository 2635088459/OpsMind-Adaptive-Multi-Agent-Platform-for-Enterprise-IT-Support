# SPEC-TW-015 — API Contract

This SPEC is event-driven and exposes no public HTTP endpoint.

Consumer input: `approval.granted.v1`.

Internal outcomes: `APPLIED`, `DUPLICATE`, `STALE`, `REJECTED_BUSINESS_RULE`, `DLQ_SCHEMA_INVALID`, `DLQ_WRONG_PRODUCER`.
