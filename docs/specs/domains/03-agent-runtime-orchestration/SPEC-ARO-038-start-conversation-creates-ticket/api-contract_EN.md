# SPEC-ARO-038 — API Contract

Goal: support `Start Conversation Creates Ticket`.

- `POST /api/v1/conversations`, public-facing (a real employee JWT, not an `/internal/` admin-only path).
- Request: `{}` (title/description/category are supplied on the first message, not at conversation start — matches domain 09's own `04-use-cases` UC-EP-01).
- Response `201`: `{conversationId, startedAt}` (matches domain 09's `05-api-contracts` §2.1 exactly).
- Depends on SPEC-ARO-043's outbound service identity to authenticate the call to `02-ticket-workflow`.
