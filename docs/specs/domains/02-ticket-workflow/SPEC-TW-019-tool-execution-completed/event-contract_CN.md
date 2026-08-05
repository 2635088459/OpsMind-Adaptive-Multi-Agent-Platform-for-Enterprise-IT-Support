# SPEC-TW-019 — 事件契约

消费：`tool.execution.completed.v1`。

发布：`ticket.tool-execution-completed-applied.v1`。

发布 payload 包含 `toolExecutionId`、`toolResultId`、`workflowId`、`actionId`、`previousStatus = EXECUTING`、`newStatus = VERIFYING`、`completedAt`。
