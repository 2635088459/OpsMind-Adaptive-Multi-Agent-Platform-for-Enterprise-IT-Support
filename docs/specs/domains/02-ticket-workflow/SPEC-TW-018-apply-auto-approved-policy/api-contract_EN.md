# SPEC-TW-018 — API Contract

Event/adapter-driven, no public HTTP endpoint.

Input: `policy.action-auto-approved.v1` or internal `PolicyDecision`.

Outcomes: `APPLIED`, `DUPLICATE`, `STALE`, `REJECTED_BUSINESS_RULE`, `DLQ_SCHEMA_INVALID`, `DLQ_WRONG_PRODUCER`.
