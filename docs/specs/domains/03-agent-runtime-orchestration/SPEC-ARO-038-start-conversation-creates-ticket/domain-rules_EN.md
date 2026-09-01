# SPEC-ARO-038 — Domain Rules

Goal: support `Start Conversation Creates Ticket`.

- Ticket creation and workflow-instance creation are two real, separate calls (a cross-service HTTP call, then a local domain command) — they are never merged into one fake atomic step.
- On workflow-instance-creation failure after a successful ticket creation, no compensating cancellation of the ticket is attempted by this spec — the ticket remains a real, valid record; a human/support-console path can still pick it up.
- The requester's identity (JWT `sub`) is recorded as the ticket's real requester — never a service-account identity standing in for the employee.
