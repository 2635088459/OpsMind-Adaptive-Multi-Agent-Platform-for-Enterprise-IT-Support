# SPEC-ARO-040 — Test Plan

Goal: support `Confirm/Decline With Bounded Wait`.

- Integration test: full round trip through `05-tool-integration-gateway`, asserting the bounded wait genuinely resolves to `"done"` for a fast tool.
- Timeout-path test: a simulated slow tool completes after the bounded wait — assert `"still-processing"` is returned, and the eventual real completion is still correctly recorded once it arrives (not lost).
- Integration test: the high-risk path genuinely creates a real governance approval request, reusing the exact chain already proven live during the 2026-09-01 integration verification.
- Idempotency test: concurrent duplicate confirm/decline requests for the same `actionId` produce exactly one real side effect.
