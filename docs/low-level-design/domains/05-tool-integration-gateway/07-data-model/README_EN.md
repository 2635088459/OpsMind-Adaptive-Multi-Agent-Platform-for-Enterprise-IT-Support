# 07 Data Model

## Database

MVP uses PostgreSQL. Business state, outbox, processed events, audit refs, and connector registry are persisted in this domain database.

## Tables

### `tool_requests`

Core columns:

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

Unique key:

- `(workflow_instance_id, agent_task_id, idempotency_key)`

### `tool_executions`

Core columns:

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

Unique keys:

- `(tool_request_id, attempt_number)`
- `(connector_id, operation_key)`

### `tool_connectors`

Stores connector registry.

Core columns:

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

Unique key:

- `(connector_id, version)`

### `tool_results`

Core columns:

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

Core columns:

- `credential_binding_id`
- `connector_id`
- `tenant_id`
- `scope`
- `vault_ref`
- `rotation_version`
- `status`
- `last_used_at`

Credential values must not be stored.

### `tool_audit_records`

Stores mandatory business audit records.

Core columns:

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

Transactional outbox consistent with other domains.

Core columns:

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

Deduplicates consumed approval/policy/workflow events.

Unique key:

- `(event_id, consumer_name)`

## Indexes

Required indexes:

- `tool_requests(status, created_at)`
- `tool_requests(ticket_id, ticket_cycle_id)`
- `tool_requests(workflow_instance_id, agent_task_id)`
- `tool_executions(status, lease_expires_at)`
- `tool_executions(tool_request_id, attempt_number)`
- `outbox_events(status, available_at)`
- `processed_events(event_id, consumer_name)`

## Retention

- ToolRequest/Execution metadata is retained long term for audit.
- Raw output is deleted or archived according to classification and policy retention.
- Redacted results/evidence refs may enter Memory Knowledge, but provenance must be preserved.

