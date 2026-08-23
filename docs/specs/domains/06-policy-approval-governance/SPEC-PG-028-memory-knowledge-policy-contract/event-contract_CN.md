# Event Contract — SPEC-PG-028

## 事件原则

- 发布事件必须经 `outbox_events`；
- 消费事件必须写 `processed_events(eventId, consumerName)`；
- 事件必须携带 source linkage、correlationId、causationId；
- event payload 不得携带敏感原始 input 或 secret。

## 相关事件

本 spec 可能涉及：

- `policy.decision.created.v1`
- `approval.requested.v1`
- `approval.granted.v1`
- `approval.denied.v1`
- `approval.expired.v1`
- `approval.cancelled.v1`
- `policy.published.v1`
- `policy.rule.changed.v1`
- `tool.approval.required.v1`
- `workflow.approval.required.v1`
- `ticket.approval.required.v1`

具体 payload 必须与 `06-policy-approval-governance/06-event-contracts` 对齐。
