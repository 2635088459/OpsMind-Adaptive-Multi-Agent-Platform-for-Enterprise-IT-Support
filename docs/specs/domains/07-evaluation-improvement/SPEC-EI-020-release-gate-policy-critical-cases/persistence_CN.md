# Persistence Design — SPEC-EI-020

## 数据影响

本 spec 只能写入 `evaluation.*` schema。根据 `02-business-invariants, 04-use-cases` 的映射，可能涉及：

- `evaluation_datasets`
- `evaluation_test_cases`
- `evaluation_runs`
- `evaluation_scores`
- `regression_reports`
- `improvement_candidates`
- `evaluation_outbox_events`
- `evaluation_processed_events`
- `evaluation_audit_records`

## 持久化规则

- 所有表必须包含 stable id、created_at/updated_at 或等价时间字段；
- version、hash、source reference 和 correlation id 必须进入可查询字段或 JSONB metadata；
- final record 不允许原地语义修改，只能 append、supersede 或创建新 version；
- outbox/audit 必须与业务状态变更同事务提交；
- sensitive evidence 只保存 redacted form 或 artifact reference。

## 索引与约束

- command idempotency key 必须有唯一约束；
- `(dataset_id, case_key)`、`run_key`、`(run_id, test_case_id, dimension, grader_version)` 等组合必须防重；
- query path 使用 run、dataset、candidate、status、created_at 建索引；
- JSONB 字段只用于扩展 evidence/details，不替代核心查询键。
