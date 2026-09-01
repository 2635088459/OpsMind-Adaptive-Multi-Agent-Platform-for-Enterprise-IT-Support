# SPEC-ARO-040 — 领域规则

目标：支撑 `确认/拒绝与限时同步等待`。

- `AWAITING_USER_CONFIRMATION` 是一个全新的、一等公民的 `AgentTaskState`；不建模成某个既有状态的特殊情况。
- `confirm` 走哪个分支（工具派发 vs 治理审批）完全由 SPEC-ARO-039 已经产出的 `ProposedAction.riskLevel` 决定——本 spec 从不重新判定或覆盖这个风险分级。
- 处于 `AWAITING_USER_CONFIRMATION` 的任务不能被既有异步 worker 路径 claim/complete——与 `WAITING_TOOL`（SPEC-ARO-019）同样的 `_require_active_claim()` 式守卫，出于相同的道理适用于这里。
