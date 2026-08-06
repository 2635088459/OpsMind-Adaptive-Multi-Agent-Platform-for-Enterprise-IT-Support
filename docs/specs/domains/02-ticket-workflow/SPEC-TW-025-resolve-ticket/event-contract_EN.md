# SPEC-TW-025 — Event Contract

Publishes: `ticket.resolved-with-verification.v1`.

Payload includes `verificationId`, `verificationEvidenceId`, `resolutionCycleId`, `resolutionCode`, and `resolvedAt`.

May also publish generic `ticket.resolved.v1`, but duplicate consumer side effects must be avoided.
