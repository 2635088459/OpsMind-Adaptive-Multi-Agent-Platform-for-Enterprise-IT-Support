# SPEC-TW-020 — Event Contract

Consumes: `tool.execution.failed.v1`.

Publishes: `ticket.tool-execution-failed-applied.v1`.

Payload includes `toolExecutionId`, `workflowId`, `actionId`, `failureCode`, `failureClass`, `previousStatus`, and `newStatus`.
