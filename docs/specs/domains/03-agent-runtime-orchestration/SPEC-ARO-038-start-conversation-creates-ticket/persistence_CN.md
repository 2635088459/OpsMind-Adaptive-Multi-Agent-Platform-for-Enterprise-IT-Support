# SPEC-ARO-038 — Persistence Design

目标：支撑 `发起会话即建单`。

- `agent-runtime-service` 这一侧不新建表——就是一条正常的新 `workflow_instances` 行（`workflow_type="conversational_intake"`）。
- 真实的工单行由 `02-ticket-workflow` 写在它自己的 schema 里，经由它自己的真实端点——本服务从不直接写入。
- `Idempotency-Key` 复用 `agent-runtime-service` 其他命令已经在用的既有幂等机制。
