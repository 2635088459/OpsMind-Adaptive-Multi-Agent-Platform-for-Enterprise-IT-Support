# SPEC-TW-016 — Apply Approval Rejected（应用审批拒绝）

## 1. 目标

消费 trusted `approval.rejected.v1`，验证其匹配当前 open approval request，将 request 标记为 `REJECTED`，并把 Ticket 从 `WAITING_FOR_APPROVAL` 恢复到 `IN_PROGRESS`。

拒绝后的 approval 不得授权任何 Tool Execution。

## 2. 范围

包含 event consumer、producer/schema 校验、引用匹配、幂等、stale 分类、`ticket.approval-rejected-applied.v1`。不包含审批 UI 或 Tool Execution。
