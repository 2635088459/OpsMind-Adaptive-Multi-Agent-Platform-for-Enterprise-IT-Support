# SPEC-ARO-039 — Persistence Design

目标：支撑 `消息轮次内联执行`。

- 不新建表。每轮次一条新的 `agent_tasks` 记录（`task_type="process_user_message"`）和一条新的 `checkpoints` 记录，用既有 schema。
- 消息文本/附件引用存在任务既有的 `inputPayload` 字段里，按 `01-domain-model` 已经要求的方式做 schema 版本化——不新增列。
- checkpoint payload 里从不写入明文密钥或原始工具凭据，遵循既有 checkpoint 不变量。
