# Event Contract — SPEC-TG-018

## 事件原则

- 发布事件必须经 `outbox_events`；
- 消费事件必须写 `processed_events(eventId, consumerName)`；
- event payload 不得携带 secret 或未脱敏 raw output；
- `correlationId` / `causationId` 必须贯穿 Runtime、Gateway、Policy、Memory。

## 相关事件

本 spec 可能涉及：

- `tool.request.accepted.v1`
- `tool.request.rejected.v1`
- `tool.approval.required.v1`
- `approval.granted.v1`
- `approval.denied.v1`
- `tool.execution.started.v1`
- `tool.execution.retry_scheduled.v1`
- `tool.completed.v1`
- `tool.connector.health_changed.v1`

具体事件 payload 必须与 `05-tool-integration-gateway/06-event-contracts` 对齐。
