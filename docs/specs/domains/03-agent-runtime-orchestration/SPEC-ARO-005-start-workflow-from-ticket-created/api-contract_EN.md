# SPEC-ARO-005 — API Contract

Goal: support `Start Workflow From ticket.created`.

- APIs primarily serve internal services, workers, and admins.
- Commands must carry idempotency key or workflow version.
- Queries return Runtime state only, not authoritative Ticket state.
- Admin APIs must record audit.
