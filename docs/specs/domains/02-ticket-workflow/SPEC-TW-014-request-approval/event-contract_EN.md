# SPEC-TW-014 — Event Contract

Publishes:

```text
ticket.approval-wait-started.v1
```

Payload includes `ticketId`, `approvalRequestId`, `approvalId`, `workflowId`, `actionId`, `actionType`, `riskLevel`, and `requestedAt`; it excludes secrets and full risk-detail logs.
