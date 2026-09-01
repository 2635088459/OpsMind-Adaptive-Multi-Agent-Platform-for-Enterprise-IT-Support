# SPEC-ARO-039 — Event Contract

目标：支撑 `消息轮次内联执行`。

- 没有新增的发布事件。本 spec 是一个同步的、HTTP 请求范围内的操作，不是事件驱动的。
- 调用 `04-memory-knowledge` 做知识检索是一次同步 HTTP/RPC 调用，不是异步事件交换——与本 spec 自身的内联执行特性一致。
- 如果本轮次结果是 `escalation` 响应形状，这里不发布任何事件；真正的分诊调用（会真实对接 `02-ticket-workflow` 自己的端点）属于 SPEC-ARO-041。
