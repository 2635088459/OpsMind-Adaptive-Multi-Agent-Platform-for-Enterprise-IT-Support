# SPEC-ARO-028 — API Contract

Goal: support `Recovery Scanner and Checkpoint Restore`.

- APIs primarily serve internal services, workers, and admins.
- Commands must carry idempotency key or workflow version.
- Queries return Runtime state only, not authoritative Ticket state.
- Admin APIs must record audit.
