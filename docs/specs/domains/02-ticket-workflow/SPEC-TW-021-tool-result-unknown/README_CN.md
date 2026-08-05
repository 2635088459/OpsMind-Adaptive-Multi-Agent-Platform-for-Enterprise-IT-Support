# SPEC-TW-021 — Tool Result Unknown（工具结果未知）

## 1. 目标

消费 trusted `tool.execution.result-unknown.v1`，记录未知结果和潜在外部副作用，并阻止系统盲目重试同一 Tool Execution。

未知结果必须进入 `ESCALATED`、reconciliation-required 或明确人工核查路径。

## 2. 范围

包含：

- unknown result event consumer；
- uncertainty/evidence 保存；
- `EXECUTING -> ESCALATED`；
- duplicate/stale/DLQ 分类；
- timeline、audit、outbox；
- reconciliation hook。

不包含：

- 自动重试；
- 自动补偿；
- 人工核查 UI；
- Verification/Resolve。

## 3. 核心规则

- Ticket 必须为 `EXECUTING`；
- event 必须匹配当前 execution attempt；
- unknown side effect 不得回到 `IN_PROGRESS` 后自动重试；
- 必须保存 evidence reference；
- duplicate 不重复升级；
- late completed event 不得静默覆盖 unknown result。
