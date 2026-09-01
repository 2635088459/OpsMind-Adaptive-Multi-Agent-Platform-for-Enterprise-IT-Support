# SPEC-ARO-042 — Acceptance Criteria

Goal: support `Resume Conversation Query`.

- Returning to the portal without a known `conversationId` still resolves the correct, most recent conversation for that employee.
- A cross-employee query attempt is rejected/returns nothing, never another employee's conversation.
- `GET /api/v1/conversations/{conversationId}` returns a shape consistent with domain 09's own frontend type expectations.
