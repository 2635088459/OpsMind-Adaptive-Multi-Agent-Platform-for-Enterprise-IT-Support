# SPEC-TW-020 — Tool Execution Failed（工具执行失败）

## 1. 目标

消费 trusted `tool.execution.failed.v1`，记录失败结果，并根据 failure class 将 Ticket 安全恢复到 `IN_PROGRESS` 或标记为 `FAILED`。

Known-safe failure 表示工具未产生未知外部副作用，可以继续调查或重新规划。Internal pipeline failure 表示执行链路本身失败，需要显式恢复策略。

## 2. 范围

包含 failure classification、producer/schema 校验、引用匹配、duplicate/stale 分类、timeline、audit、outbox。

不包含盲目重试、人工升级处理、Verification。

## 3. 核心规则

- Ticket 必须为 `EXECUTING`；
- event 必须匹配当前 execution attempt；
- known-safe failure 回到 `IN_PROGRESS`；
- pipeline/internal failure 可进入 `FAILED`；
- unsafe/unknown side effect 不属于本 SPEC，应走 SPEC-TW-021；
- duplicate 不产生重复业务效果。
