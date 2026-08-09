# SPEC-TW-035 测试计划

## 单元测试

- policy allow/deny decision；
- fail-closed 分支；
- redaction 和 low-cardinality telemetry；
- 缺失 actor/context/operation 的 validation。

## 集成测试

- 与至少两个既有 Phase 01～08 endpoint 集成；
- 拒绝路径不写业务 outbox 成功事件；
- audit/metric/trace 被记录；
- policy bypass 尝试失败。

## 回归测试

- create/query/timeline/assignment/escalation/close golden path 仍通过；
- error contract 不泄漏策略内部细节。
