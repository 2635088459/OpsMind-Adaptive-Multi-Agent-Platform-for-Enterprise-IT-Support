# SPEC-ARO-029 — API Contract

Goal: support `Expired Lease Task Recovery`.

- APIs primarily serve internal services, workers, and admins.
- Commands must carry idempotency key or workflow version.
- Queries return Runtime state only, not authoritative Ticket state.
- Admin APIs must record audit.
