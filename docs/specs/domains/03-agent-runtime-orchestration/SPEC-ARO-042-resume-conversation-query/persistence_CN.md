# SPEC-ARO-042 — Persistence Design

目标：支撑 `恢复会话查询`。

- 不新建表。只读取既有的 `workflow_instances`。
- 如果 `workflow_instances` 目前没有"创建者身份"字段，可能需要一次迁移来新增（可空，新记录起向前回填）——在实施时对着真实 schema 确认，本文档不假设。
- "最近一次"查询的性能可能需要一个 (subject, updated_at) 的支撑索引——留给实施阶段。
