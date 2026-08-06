# SPEC-TW-024 — Verification Failure

## 1. Goal

Consume trusted verification failure result, classify retryable, unsafe, limit reached, and pipeline failure, and safely move the ticket to `IN_PROGRESS`, `ESCALATED`, or `FAILED`.

The third failure or unsafe result escalates.
