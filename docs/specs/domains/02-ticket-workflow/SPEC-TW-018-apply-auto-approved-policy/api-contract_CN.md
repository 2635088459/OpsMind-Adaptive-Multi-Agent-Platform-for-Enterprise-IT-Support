# SPEC-TW-018 — API 契约

事件/adapter 驱动，无 public HTTP endpoint。

输入：`policy.action-auto-approved.v1` 或内部 `PolicyDecision`。

结果：`APPLIED`、`DUPLICATE`、`STALE`、`REJECTED_BUSINESS_RULE`、`DLQ_SCHEMA_INVALID`、`DLQ_WRONG_PRODUCER`。
