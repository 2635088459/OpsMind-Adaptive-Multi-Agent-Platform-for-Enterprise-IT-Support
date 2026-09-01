# SPEC-ARO-037 — Persistence Design

目标：支撑 `对话式接入工作流类型`。

- 不新建表，原样复用 `workflow_instances`/`agent_tasks`。
- 迁移只是扩大既有 `workflow_type`/`task_type` 列的允许值集合（CHECK 约束或枚举类型，具体看原列用的是哪种机制）——不新增、不改名、不删除任何列。
- `checkpoints`/`tool_requests` 的 payload/schema 版本不变。
