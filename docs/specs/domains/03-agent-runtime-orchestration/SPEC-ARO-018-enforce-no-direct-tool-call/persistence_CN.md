# SPEC-ARO-018 — 持久化设计

目标：支撑 `禁止 Agent 直连 Tool`。

- 表结构必须位于 Agent Runtime 边界内。
- 写模型必须支持 idempotency、version 或唯一键约束。
- 外部副作用前必须写 checkpoint 或 outbox。
- payload 必须 schema-versioned，且不能保存 secret。
