# SPEC-ARO-016 — API Contract

Goal: support `Stale Generation Worker Result`.

- APIs primarily serve internal services, workers, and admins.
- Commands must carry idempotency key or workflow version.
- Queries return Runtime state only, not authoritative Ticket state.
- Admin APIs must record audit.
