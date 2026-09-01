# SPEC-ARO-041 — API Contract

目标：支撑 `借助既有分诊转人工`。

- 没有新的公开端点。由 SPEC-ARO-039 的消息轮次逻辑判定需要转人工时内部触发。
- 内部调用 `02-ticket-workflow` 已经真实存在的 `POST /api/v1/tickets/{ticketId}/triage`（遵循该 domain 自己的 `05-api-contracts`）。
- 依赖 SPEC-ARO-043 的服务身份为这次外呼提供鉴权。
