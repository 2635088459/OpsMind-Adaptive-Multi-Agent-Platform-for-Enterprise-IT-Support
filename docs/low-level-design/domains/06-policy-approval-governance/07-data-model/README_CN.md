# 07 Data Model

## 数据库

MVP 使用 PostgreSQL，保存 policy versions、policy decisions、approval requests、approval decisions、governance audit、outbox 和 processed events。

## 表设计

### `policies`

- `policy_id` PK
- `policy_name`
- `scope`
- `status`
- `created_at`
- `updated_at`

### `policy_versions`

- `policy_version_id` PK
- `policy_id` FK
- `version`
- `status`
- `rules`
- `effective_from`
- `effective_to`
- `created_by`
- `reviewed_by`
- `published_by`
- `published_at`

唯一键：`(policy_id, version)`。

### `policy_decisions`

- `policy_decision_id` PK
- `decision_key`
- `input_hash`
- `subject_type`
- `subject_id`
- `action_type`
- `resource_type`
- `resource_id`
- `tenant_id`
- `source_domain`
- `source_request_id`
- `ticket_id`
- `workflow_instance_id`
- `effect`
- `risk_level`
- `approval_required`
- `constraints`
- `reason_codes`
- `policy_id`
- `policy_version`
- `created_at`
- `expires_at`

唯一键：`(decision_key, input_hash)`。

### `approval_requests`

- `approval_request_id` PK
- `request_key`
- `request_hash`
- `source_domain`
- `source_request_id`
- `ticket_id`
- `workflow_instance_id`
- `tool_request_id`
- `requested_by_type`
- `requested_by_id`
- `approval_type`
- `risk_level`
- `constraints`
- `status`
- `expires_at`
- `created_at`
- `updated_at`

唯一键：`(source_domain, source_request_id, request_key)`。

### `approval_decisions`

- `approval_decision_id` PK
- `approval_request_id` FK
- `decision`
- `decided_by_type`
- `decided_by_id`
- `reason`
- `conditions`
- `separation_of_duties_result`
- `decided_at`

唯一键：`approval_request_id`，保证一个审批请求只有一个 final decision。

### `governance_audit_records`

记录所有 policy/approval/override/admin action。

### `outbox_events`

发布 `approval.*`、`policy.*`、`governance.*` 事件。

### `processed_events`

消费外部事件去重。

## 索引

- `policy_decisions(source_domain, source_request_id)`
- `approval_requests(status, expires_at)`
- `approval_requests(ticket_id)`
- `approval_requests(workflow_instance_id)`
- `approval_requests(tool_request_id)`
- `outbox_events(status, available_at)`
- `processed_events(event_id, consumer_name)`

