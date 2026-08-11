# SPEC-ARO-031 — API Contract

Goal: support `Admin Reconciliation and Repair`.

- APIs primarily serve internal services, workers, and admins.
- Commands must carry idempotency key or workflow version.
- Queries return Runtime state only, not authoritative Ticket state.
- Admin APIs must record audit.
