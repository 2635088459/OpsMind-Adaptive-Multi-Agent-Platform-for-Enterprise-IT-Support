# SPEC-TW-017 — Apply Approval Expired（应用审批过期）

## 1. 目标

消费 trusted `approval.expired.v1` 或本地过期判定，将匹配的 open approval request 标记为 `EXPIRED`，并让 Ticket 从 `WAITING_FOR_APPROVAL` 回到 `IN_PROGRESS`。

过期 approval 永远不能授权 execution。
