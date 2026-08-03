# SPEC-TW-018 — Apply Auto-Approved Policy（应用自动批准策略）

## 1. 目标

消费 trusted `policy.action-auto-approved.v1` 或由本地 policy adapter 返回的低风险自动批准结果，为 pending action 保存明确 authorization reference。

Auto-approved 不是“无需审批”，而是“由策略显式批准”。Phase 05 只保存授权结果，不执行工具。

## 2. 范围

包含：

- policy event/adapter input；
- Ticket、workflow、action、risk context 匹配；
- 保存 `AUTO_APPROVED` request/decision；
- 发布 `ticket.auto-approval-applied.v1`；
- duplicate/stale/wrong producer 分类。

不包含 Tool Execution、策略编辑器、高级风险模型。
