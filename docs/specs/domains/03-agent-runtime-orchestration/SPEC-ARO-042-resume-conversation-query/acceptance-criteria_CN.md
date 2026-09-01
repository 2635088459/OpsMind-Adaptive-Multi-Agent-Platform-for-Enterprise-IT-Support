# SPEC-ARO-042 — Acceptance Criteria

目标：支撑 `恢复会话查询`。

- 在不知道 `conversationId` 的情况下重新打开门户，依然能解析出该员工正确的最近一次会话。
- 跨员工查询尝试被拒绝/返回空，从不返回另一个员工的会话。
- `GET /api/v1/conversations/{conversationId}` 返回的形状与 09 号 domain 自己的前端类型预期一致。
