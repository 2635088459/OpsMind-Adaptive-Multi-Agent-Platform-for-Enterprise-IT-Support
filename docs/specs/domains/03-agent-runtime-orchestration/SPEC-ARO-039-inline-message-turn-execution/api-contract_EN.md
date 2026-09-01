# SPEC-ARO-039 — API Contract

Goal: support `Inline Message Turn Execution`.

- `POST /api/v1/conversations/{conversationId}/messages`, `Idempotency-Key` required.
- Request: `{text, attachmentRefs[]}` (matches domain 09's `05-api-contracts` §2.2 exactly).
- Response: a discriminated union, exactly one of `{type: "text", text}` / `{type: "proposedAction", actionId, summary, riskLevel, requiresConfirmation}` / `{type: "escalation", ticketId, displayId, reason, assignedTeam}`.
- Attachment references (`attachmentRefs`) are resolved against the shared attachments capability (chartered separately, see `09-employee-portal`'s own `05-api-contracts` §3) — this spec only consumes already-uploaded, `ready`-state references.
