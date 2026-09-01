# SPEC-ARO-041 — Test Plan

Goal: support `Escalation Via Existing Triage`.

- Integration test against the real `02-ticket-workflow` triage endpoint (docker-compose stack).
- End-to-end test: start a conversation, send a message that genuinely requires escalation, and confirm the ticket appears correctly in `10-support-console`'s queue afterward.
- Failure test: `ticket-workflow-service` unavailable during escalation → the workflow instance does not silently reach a false terminal state; the failure is surfaced and retryable.
