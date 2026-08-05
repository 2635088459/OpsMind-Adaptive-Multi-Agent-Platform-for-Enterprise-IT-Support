# SPEC-TW-020 — 事件契约

消费：`tool.execution.failed.v1`。

发布：`ticket.tool-execution-failed-applied.v1`。

Payload 包含 `toolExecutionId`、`workflowId`、`actionId`、`failureCode`、`failureClass`、`previousStatus`、`newStatus`。
