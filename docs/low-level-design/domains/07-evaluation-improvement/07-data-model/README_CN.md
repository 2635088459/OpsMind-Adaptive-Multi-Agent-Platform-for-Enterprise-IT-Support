# 07 Data Model

## Schema Ownership

07 拥有 PostgreSQL schema：

```text
evaluation.*
```

其他服务不得写入 `evaluation.*` 表。07 不写其他服务 schema。

## 核心表

### evaluation_datasets

- `dataset_id`
- `name`
- `version`
- `domain`
- `status`
- `case_count`
- `lineage_parent_id`
- `created_by`
- `published_by`
- `created_at`
- `published_at`

唯一键：`(name, version)`。

### evaluation_test_cases

- `test_case_id`
- `dataset_id`
- `case_key`
- `scenario`
- `tags jsonb`
- `user_request_redacted`
- `mock_system_state jsonb`
- `ground_truth jsonb`
- `allowed_tools jsonb`
- `forbidden_tools jsonb`
- `required_approval boolean`
- `verification_condition jsonb`
- `criticality`
- `input_hash`

唯一键：`(dataset_id, case_key)`。

### evaluation_runs

- `run_id`
- `run_key`
- `dataset_id`
- `dataset_version`
- `target_version`
- `baseline_version`
- `grader_bundle_version`
- `status`
- `triggered_by`
- `started_at`
- `completed_at`

唯一键：`run_key`。

### evaluation_scores

- `score_id`
- `run_id`
- `test_case_id`
- `dimension`
- `score numeric`
- `passed boolean`
- `threshold numeric`
- `grader_type`
- `grader_version`
- `evidence_ref`
- `failure_code`
- `details jsonb`

索引：`(run_id, dimension)`、`(test_case_id, dimension)`。

### regression_reports

- `report_id`
- `run_id`
- `baseline_run_id`
- `overall_decision`
- `gate_results jsonb`
- `metric_diffs jsonb`
- `critical_failures jsonb`
- `recommendation`
- `created_at`

### improvement_candidates

- `candidate_id`
- `candidate_type`
- `source_run_id`
- `target_component`
- `proposed_change jsonb`
- `risk_level`
- `status`
- `approval_request_id`
- `canary_plan jsonb`
- `created_by`
- `created_at`
- `updated_at`

### evaluation_outbox_events / processed_events / audit_records

07 使用本地 outbox、processed event 和 audit 表，字段与其他域保持一致：`event_id`、`event_type`、`aggregate_id`、`payload`、`status`、`attempt_count`、`created_at`、`published_at`。

## Artifact 引用

LangSmith experiment、trace、judge explanation、大型 report 文件不直接存入主表，只保存：

- `artifact_provider`
- `artifact_uri`
- `artifact_hash`
- `retention_until`

