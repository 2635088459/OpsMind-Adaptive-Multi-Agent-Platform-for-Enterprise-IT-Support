# SPEC-TW-023 — Event Contract

Consumes: `verification.completed.v1`.

Publishes: `ticket.verification-success-applied.v1`.

Payload includes `verificationId`, `verificationEvidenceId`, `workflowId`, `resolutionCycleId`, `attemptNumber`, and `completedAt`.
