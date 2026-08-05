# SPEC-TW-019 — API 契约

事件驱动，无 public HTTP endpoint。

消费：

```text
tool.execution.completed.v1
```

结果分类：

```text
APPLIED
DUPLICATE
STALE
REJECTED_BUSINESS_RULE
DLQ_SCHEMA_INVALID
DLQ_WRONG_PRODUCER
```
