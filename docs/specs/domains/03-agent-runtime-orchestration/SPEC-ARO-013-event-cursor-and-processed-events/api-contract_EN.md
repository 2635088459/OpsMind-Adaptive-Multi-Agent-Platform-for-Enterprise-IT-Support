# SPEC-ARO-013 — API Contract

Goal: support `Event Cursor and Processed Events`.

- APIs primarily serve internal services, workers, and admins.
- Commands must carry idempotency key or workflow version.
- Queries return Runtime state only, not authoritative Ticket state.
- Admin APIs must record audit.
