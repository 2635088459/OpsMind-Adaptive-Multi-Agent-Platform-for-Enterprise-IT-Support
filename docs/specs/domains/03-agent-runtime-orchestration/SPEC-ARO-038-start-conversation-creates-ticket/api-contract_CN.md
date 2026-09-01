# SPEC-ARO-038 — API Contract

目标：支撑 `发起会话即建单`。

- `POST /api/v1/conversations`，公开面向前端（真实员工 JWT，不是 `/internal/` 管理专用路径）。
- 请求：`{}`（标题/描述/分类在第一条消息里给出，不在发起会话时——与 09 号 domain 自己的 `04-use-cases` UC-EP-01 一致）。
- 响应 `201`：`{conversationId, startedAt}`（与 09 号 domain `05-api-contracts` §2.1 完全一致）。
- 依赖 SPEC-ARO-043 的外呼服务身份，为调用 `02-ticket-workflow` 提供鉴权。
