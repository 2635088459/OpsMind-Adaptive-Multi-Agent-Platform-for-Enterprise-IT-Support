# SPEC-ARO-040 — Event Contract

目标：支撑 `确认/拒绝与限时同步等待`。

- 消费 `tool.completed`/`tool.failed` 来解除限时等待——完全复用 SPEC-ARO-020 既有的消费者；本 spec 不新增任何消费者，只是在同一批已消费事件之上新增一个同步等待者。
- 高风险分支同步调用 `06-policy-approval-governance` 真实的发起审批端点（直接 HTTP 调用，不是事件）——最终的 `approval.granted`/`approval.rejected` 事件依然是 SPEC-ARO-021 既有的消费职责，本 spec 不改变它。
- `decline` 不发布任何事件，也不发起任何外呼。
