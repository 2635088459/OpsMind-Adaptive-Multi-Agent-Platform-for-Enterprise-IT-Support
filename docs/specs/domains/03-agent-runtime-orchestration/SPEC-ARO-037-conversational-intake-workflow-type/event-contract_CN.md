# SPEC-ARO-037 — Event Contract

目标：支撑 `对话式接入工作流类型`。

- 没有新增的发布或消费事件。本 spec 只是给已有聚合新增枚举值。
- 既有事件契约（`ticket.created.v1` 消费、工作流生命周期 outbox 发布）完全不受影响——它们本来就把 `workflow_type` 当作一个不透明的字符串字段处理。
