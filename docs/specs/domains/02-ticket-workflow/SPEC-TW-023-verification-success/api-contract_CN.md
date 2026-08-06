# SPEC-TW-023 — API 契约

事件驱动，无 public HTTP endpoint。

消费：`verification.completed.v1`，其中 `result = SUCCESS`。

结果：`APPLIED`、`DUPLICATE`、`STALE`、`CONFLICT_REQUIRES_RECONCILIATION`、`DLQ_SCHEMA_INVALID`。
