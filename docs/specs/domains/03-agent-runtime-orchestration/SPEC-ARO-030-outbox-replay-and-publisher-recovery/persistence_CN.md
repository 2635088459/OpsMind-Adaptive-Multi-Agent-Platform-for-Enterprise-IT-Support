# SPEC-ARO-030 — 持久化设计

目标：支撑 `Outbox Replay 与 Publisher Recovery`。

- 表结构必须位于 Agent Runtime 边界内。
- 写模型必须支持 idempotency、version 或唯一键约束。
- 外部副作用前必须写 checkpoint 或 outbox。
- payload 必须 schema-versioned，且不能保存 secret。
