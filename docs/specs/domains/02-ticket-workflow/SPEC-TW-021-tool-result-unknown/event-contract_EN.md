# SPEC-TW-021 — Event Contract

Consumes: `tool.execution.result-unknown.v1`.

Publishes: `ticket.tool-result-unknown-recorded.v1`.

Payload includes `toolExecutionId`, `workflowId`, `actionId`, `unknownReason`, `evidenceReferences`, `previousStatus`, and `newStatus`.
