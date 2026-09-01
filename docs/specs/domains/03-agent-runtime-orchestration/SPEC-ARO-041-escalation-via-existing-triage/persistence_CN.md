# SPEC-ARO-041 — Persistence Design

目标：支撑 `借助既有分诊转人工`。

- 不新建表。`WorkflowInstance` 迁移到一个既有的终态（例如 `COMPLETED`），转人工原因记录在它自己既有的字段里——不新增列。
- 工单自己的分诊相关字段（分类、子分类、支持队列）由 `02-ticket-workflow` 写在它自己的 schema 里，经由它自己的真实端点。
