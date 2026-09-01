# SPEC-ARO-039 — 领域规则

目标：支撑 `消息轮次内联执行`。

- `process_user_message` 任务遵循与其他所有任务类型相同的 `AgentTask` 领域模型，但它的状态迁移在请求内同步发生，而不是经由 `claim`/`complete`。
- 从 `04-memory-knowledge` 检索到的知识库内容用于支撑回复，检索失败时从不编造——降级为更朴素的回答或转人工，而不是虚构一个"引用"。
- 本轮次写入的 checkpoint 是一个真实、可恢复的快照（遵循 `01-domain-model` 既有的 checkpoint 要求），不是占位符。
