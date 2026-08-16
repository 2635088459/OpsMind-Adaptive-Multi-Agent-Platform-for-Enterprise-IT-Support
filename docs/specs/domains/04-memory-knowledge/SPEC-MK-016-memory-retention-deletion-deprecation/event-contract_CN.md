# SPEC-MK-016 Event Contract

## Event 范围

- 消费事件必须使用 shared envelope，并以 `eventId + consumerName` 去重。
- 发布事件必须通过 `memory.outbox_events`。
- 事件 payload 必须包含足够 provenance 或 source refs。

## 与 02/03 的关系

- 来自 02 的 ticket events 只作为事实输入。
- 来自 03 的 workflow events 只作为 automation trace/evidence 输入。
- 发布给 03/07 的 memory events 不得要求下游直接改变 Ticket/Workflow 状态。
