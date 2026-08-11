# SPEC-ARO-019 — API Contract

Goal: support `Dispatch Tool Request Through Gateway`.

- APIs primarily serve internal services, workers, and admins.
- Commands must carry idempotency key or workflow version.
- Queries return Runtime state only, not authoritative Ticket state.
- Admin APIs must record audit.
