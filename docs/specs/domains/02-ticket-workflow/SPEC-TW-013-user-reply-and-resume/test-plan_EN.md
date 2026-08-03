# SPEC-TW-013 — TDD Test Plan

Cover:

- successful `WAITING_FOR_USER -> IN_PROGRESS`;
- requester ownership validation;
- request missing / wrong ticket / non-OPEN;
- reject resume from non-waiting state;
- message validation and attachment reference validation;
- duplicate reply resumes only once;
- idempotency replay/conflict;
- two concurrent replies allow only one status transition;
- transaction rollback;
- `ticket.user-reply-received.v1` and `ticket.user-input-resumed.v1` contracts;
- E2E: request input -> requester reply -> resume.
