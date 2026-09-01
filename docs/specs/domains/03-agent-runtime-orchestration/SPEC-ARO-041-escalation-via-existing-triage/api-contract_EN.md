# SPEC-ARO-041 — API Contract

Goal: support `Escalation Via Existing Triage`.

- No new public endpoint. Triggered internally by SPEC-ARO-039's message-turn logic when it determines escalation is needed.
- Internally calls `02-ticket-workflow`'s already-real `POST /api/v1/tickets/{ticketId}/triage` (per that domain's own `05-api-contracts`).
- Depends on SPEC-ARO-043's service identity for authenticating this outbound call.
