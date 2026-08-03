# SPEC-TW-015 — Apply Approval Granted（应用审批通过）

## 1. 目标

消费 trusted `approval.granted.v1` 事件，验证其匹配当前 Ticket、workflow、action、approval 和过期时间，并把 open approval request 标记为 `GRANTED`。

成功后 Ticket 从 `WAITING_FOR_APPROVAL` 回到 `IN_PROGRESS`，保存 authorization reference，供 Phase 06 Tool Execution 使用。本 SPEC 不执行工具。

## 2. 范围

包含：approval event consumer、producer/schema 校验、引用匹配、duplicate 幂等、stale 分类、`ticket.approval-granted-applied.v1`。

不包含：Approval Service、Tool Execution、Verification。
