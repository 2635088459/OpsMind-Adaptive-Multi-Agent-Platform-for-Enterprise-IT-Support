# SPEC-ARO-035 — 事件契约

目标：支撑 `Event 与 API 契约测试框架`。

- 消费事件必须检查 event id、producer、schema version 和 correlation。
- 发布事件必须通过 Runtime outbox。
- Event envelope 必须包含 correlationId、causationId、ticketId 和 workflowInstanceId。
- Duplicate/stale/invalid event 不得重复推进 Workflow。
