# SPEC-TW-023 — Verification Success

## 1. Goal

Consume trusted successful `verification.completed.v1`, verify it belongs to the current ticket, workflow, resolution cycle, and verification attempt, and store trusted verification evidence.

This SPEC applies success and marks resolution-ready; `SPEC-TW-025` moves the ticket into `RESOLVED`.

## 2. Scope

Includes event consumer, producer/schema validation, reference matching, evidence snapshot, duplicate/stale classification, and `ticket.verification-success-applied.v1`.

Excludes manual resolution summary, close, and reopen.
