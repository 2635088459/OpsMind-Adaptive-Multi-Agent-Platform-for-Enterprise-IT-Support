# SPEC-TW-014 — 事件契约

发布：

```text
ticket.approval-wait-started.v1
```

Payload 包含 `ticketId`、`approvalRequestId`、`approvalId`、`workflowId`、`actionId`、`actionType`、`riskLevel`、`requestedAt`，不包含 secret 或完整风险细节日志。
