# Persistence — SPEC-UA-028

> Domain: User Access And Authentication
>
> Phase: 06 — Cross Domain Identity Contracts
>
> Service: `user-access-authentication-service`
>
> LLD mapping: `06-event-contracts, 08-transaction-and-outbox`
>
> Status: planned

## Persistence

- Persist only owned user mappings, role assignments, session/revocation metadata, step-up challenges, audit, outbox, and processed events.
- Sensitive columns use encryption or irreversible hashes; tokens and credentials are never stored.
- State transitions and audit/outbox commit in one database transaction.
- Migrations are forward-safe and repeatably verified with retention/deletion rules.
