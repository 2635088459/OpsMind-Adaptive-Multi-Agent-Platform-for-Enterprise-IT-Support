# SPEC-ARO-040 — Persistence Design

目标：支撑 `确认/拒绝与限时同步等待`。

- 不新建表。复用既有的 `agent_tasks`（新增 `AWAITING_USER_CONFIRMATION` 状态值）、`tool_requests`、`checkpoints`。
- 高风险分支真实的 `approval_requests` 记录由 `06-policy-approval-governance` 写在它自己的 schema 里，经由它自己的真实端点——本服务从不直接写入。
- `decline` 除了任务本身的终态迁移之外，不在任何地方写入新记录。
