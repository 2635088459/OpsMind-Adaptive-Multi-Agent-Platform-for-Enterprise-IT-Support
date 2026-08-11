# SPEC-ARO-020 — 测试计划

目标：支撑 `消费 tool.completed 与 tool.failed`。

- 单元测试覆盖 domain transition 和 invariant。
- 集成测试覆盖 persistence、outbox 和 processed-event。
- 契约测试覆盖 API/event schema。
- 故障测试覆盖 duplicate、stale、retry 或 crash window。
