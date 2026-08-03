# SPEC-TW-012 — TDD Test Plan

Cover:

- successful `IN_PROGRESS -> WAITING_FOR_USER`;
- reject non-`IN_PROGRESS`;
- reject missing assignee;
- reject existing open request;
- prompt validation and secret filtering;
- queue authorization;
- expected version conflict;
- idempotency replay/conflict;
- PostgreSQL unique partial index;
- ticket/request/history/timeline/audit/outbox in one transaction;
- `ticket.user-input-requested.v1` contract;
- E2E: assign -> start -> request user input.

Exit criteria: all unit, integration, contract, and E2E tests pass deterministically.
