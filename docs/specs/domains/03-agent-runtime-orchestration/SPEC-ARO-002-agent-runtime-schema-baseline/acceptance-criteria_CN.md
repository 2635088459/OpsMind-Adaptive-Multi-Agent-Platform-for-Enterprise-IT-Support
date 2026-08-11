# SPEC-ARO-002 — 验收标准

目标：支撑 `Agent Runtime Schema 基线`。

- 文档、代码、migration 和测试能按单 spec 闭环。
- 映射 LLD 规则全部覆盖。
- 幂等、并发和失败路径有测试。
- 不引入 Agent 直连 Tool 或 Runtime 直写 Ticket state。
