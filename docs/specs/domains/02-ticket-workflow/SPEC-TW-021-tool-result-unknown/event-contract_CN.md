# SPEC-TW-021 — 事件契约

消费：`tool.execution.result-unknown.v1`。

发布：`ticket.tool-result-unknown-recorded.v1`。

Payload 包含 `toolExecutionId`、`workflowId`、`actionId`、`unknownReason`、`evidenceReferences`、`previousStatus`、`newStatus`。
