# SPEC-TW-020 — API Contract

Event-driven, no public HTTP endpoint.

Consumes: `tool.execution.failed.v1`.

Outcomes: `APPLIED_SAFE_FAILURE`, `APPLIED_PIPELINE_FAILURE`, `DUPLICATE`, `STALE`, `DLQ_SCHEMA_INVALID`, `DLQ_WRONG_PRODUCER`.
