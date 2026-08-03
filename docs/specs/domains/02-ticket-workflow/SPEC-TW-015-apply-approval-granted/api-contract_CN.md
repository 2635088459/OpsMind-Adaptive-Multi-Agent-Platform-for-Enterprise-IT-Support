# SPEC-TW-015 — API 契约

本 SPEC 由事件驱动，不提供 public HTTP endpoint。

Consumer input：`approval.granted.v1`。

内部处理结果：`APPLIED`、`DUPLICATE`、`STALE`、`REJECTED_BUSINESS_RULE`、`DLQ_SCHEMA_INVALID`、`DLQ_WRONG_PRODUCER`。
