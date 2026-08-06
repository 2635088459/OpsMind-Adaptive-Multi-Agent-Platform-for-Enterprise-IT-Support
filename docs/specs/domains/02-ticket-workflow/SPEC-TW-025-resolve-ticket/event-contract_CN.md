# SPEC-TW-025 — 事件契约

发布：`ticket.resolved-with-verification.v1`。

Payload 包含 `verificationId`、`verificationEvidenceId`、`resolutionCycleId`、`resolutionCode`、`resolvedAt`。

可同时兼容发布通用 `ticket.resolved.v1`，但必须避免重复消费者副作用。
