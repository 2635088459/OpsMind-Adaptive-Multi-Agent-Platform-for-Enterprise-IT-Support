# SPEC-ARO-040 — API Contract

Goal: support `Confirm/Decline With Bounded Wait`.

- `POST /api/v1/conversations/{conversationId}/actions/{actionId}/confirm`, `Idempotency-Key` required.
- `POST /api/v1/conversations/{conversationId}/actions/{actionId}/decline`, `Idempotency-Key` required.
- Response for `confirm`: `{outcome: "done" | "still-processing" | "awaiting-approval", ...}` — an explicit outcome discriminator, matching the nuance domain 09's own LLD/product-vision memory already documents (the mockup's "instant ✓ done" is the common case, not the only contract shape).
- Response for `decline`: `{outcome: "declined"}`.
