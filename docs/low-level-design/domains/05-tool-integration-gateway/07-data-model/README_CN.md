# 07 Data Model

## 数据库

MVP 使用 PostgreSQL。所有业务状态、outbox、processed events、audit refs 和 connector registry 都在本域数据库中持久化。

## 表设计

### `tool_requests`

核心列：

- `tool_request_id` PK
- `idempotency_key`
- `payload_hash`
- `ticket_id`
- `ticket_cycle_id`
- `workflow_instance_id`
- `agent_task_id`
- `requested_by_type`
- `requested_by_id`
- `capability_name`
- `tool_name`
- `input_payload`
- `reason`
- `status`
- `risk_decision_ref`
- `approval_request_id`
- `created_at`
- `updated_at`
- `completed_at`

唯一键：

- `(workflow_instance_id, agent_task_id, idempotency_key)`

### `tool_executions`

核心列：

- `execution_id` PK
- `tool_request_id` FK
- `attempt_number`
- `connector_id`
- `connector_version`
- `operation_key`
- `status`
- `lease_owner`
- `lease_expires_at`
- `started_at`
- `completed_at`
- `timeout_at`
- `error_code`
- `retryable`
- `result_envelope_id`

唯一键：

- `(tool_request_id, attempt_number)`
- `(connector_id, operation_key)`

### `tool_connectors`

保存 connector registry。

核心列：

- `connector_id`
- `version`
- `name`
- `status`
- `manifest`
- `capabilities`
- `input_schema`
- `output_schema`
- `risk_level`
- `requires_approval`
- `secret_requirements`
- `network_policy`
- `timeout_policy`
- `retry_policy`
- `created_at`
- `updated_at`

唯一键：

- `(connector_id, version)`

### `tool_results`

核心列：

- `result_envelope_id` PK
- `execution_id` FK
- `status`
- `summary`
- `structured_output`
- `raw_output_ref`
- `redaction_status`
- `evidence_refs`
- `external_resource_refs`
- `error_code`
- `retryable`
- `created_at`

### `credential_bindings`

核心列：

- `credential_binding_id`
- `connector_id`
- `tenant_id`
- `scope`
- `vault_ref`
- `rotation_version`
- `status`
- `last_used_at`

不得保存 credential value。

### `tool_audit_records`

保存不可缺失的业务审计记录。

核心列：

- `audit_id`
- `actor_type`
- `actor_id`
- `action`
- `tool_request_id`
- `execution_id`
- `connector_id`
- `metadata`
- `occurred_at`

### `outbox_events`

与其他域保持一致的 transactional outbox。

核心列：

- `outbox_id`
- `aggregate_id`
- `event_type`
- `payload`
- `headers`
- `status`
- `available_at`
- `published_at`
- `attempt_count`

### `processed_events`

用于消费 approval/policy/workflow 事件去重。

唯一键：

- `(event_id, consumer_name)`

## 索引

必须建立：

- `tool_requests(status, created_at)`
- `tool_requests(ticket_id, ticket_cycle_id)`
- `tool_requests(workflow_instance_id, agent_task_id)`
- `tool_executions(status, lease_expires_at)`
- `tool_executions(tool_request_id, attempt_number)`
- `outbox_events(status, available_at)`
- `processed_events(event_id, consumer_name)`

## 保留策略

- ToolRequest/Execution metadata 长期保留，满足审计。
- raw output 按 classification 和 policy retention 删除或归档。
- redacted result/evidence refs 可进入 Memory Knowledge，但必须保留 provenance。

