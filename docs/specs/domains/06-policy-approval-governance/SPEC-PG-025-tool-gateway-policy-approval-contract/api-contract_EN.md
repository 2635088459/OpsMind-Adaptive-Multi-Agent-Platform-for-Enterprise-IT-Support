# API Contract — SPEC-PG-025

## API Impact

This spec may add or modify 06 APIs, but must preserve:

- Decision API returns governance facts and performs no business side effects.
- Approval API requires authenticated actor, idempotency key, reason, and correlation id.
- Admin Policy API requires reviewer/publisher separation of duties.
- Audit API returns metadata/hash by default, not sensitive raw input.

## Main Contract

- Input must include sourceDomain/sourceRequestId or equivalent linkage.
- Response must include stable status/effect/risk/reason code.
- conflict, denied, expired, cancelled, and evaluation failed must remain distinguishable.
- Every command API must support idempotency.
