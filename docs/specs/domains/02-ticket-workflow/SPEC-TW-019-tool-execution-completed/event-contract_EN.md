# SPEC-TW-019 — Event Contract

Consumes: `tool.execution.completed.v1`.

Publishes: `ticket.tool-execution-completed-applied.v1`.

Published payload includes `toolExecutionId`, `toolResultId`, `workflowId`, `actionId`, `previousStatus = EXECUTING`, `newStatus = VERIFYING`, and `completedAt`.
