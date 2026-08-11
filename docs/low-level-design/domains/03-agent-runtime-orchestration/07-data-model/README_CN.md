# 07 Data Model

## Schema

建议使用独立 schema：`agent_runtime`。Runtime 表不要混入 Ticket Workflow 表。

## workflow_instances

保存 Workflow Instance。

关键列：

- `id`
- `ticket_id`
- `ticket_cycle_id`
- `workflow_type`
- `definition_version`
- `state`
- `workflow_version`
- `pause_generation`
- `current_checkpoint_id`
- `created_at`
- `updated_at`
- `completed_at`

唯一键：

- `ticket_id, ticket_cycle_id, workflow_type` where state is not terminal。

## agent_tasks

保存 Agent Task。

关键列：

- `id`
- `workflow_instance_id`
- `agent_role`
- `task_type`
- `state`
- `depends_on_json`
- `input_payload_json`
- `result_payload_json`
- `failure_reason`
- `attempt`
- `max_attempts`
- `claim_owner`
- `claim_token`
- `claim_expires_at`
- `pause_generation`
- `created_at`
- `updated_at`

索引：

- `workflow_instance_id, state`
- `agent_role, state, claim_expires_at`

## checkpoints

保存恢复快照。

关键列：

- `id`
- `workflow_instance_id`
- `workflow_version`
- `checkpoint_type`
- `cursor`
- `payload_schema_version`
- `payload_json`
- `checksum`
- `created_at`

索引：

- `workflow_instance_id, created_at desc`
- `workflow_instance_id, workflow_version`

## tool_requests

保存 Tool Gateway 请求。

关键列：

- `id`
- `workflow_instance_id`
- `agent_task_id`
- `tool_name`
- `capability`
- `state`
- `gateway_correlation_id`
- `input_payload_json`
- `result_payload_json`
- `policy_snapshot_json`
- `created_at`
- `completed_at`

唯一键：

- `gateway_correlation_id`
- `agent_task_id, idempotency_key`

## processed_events

消费事件去重表。

关键列：

- `event_id`
- `event_type`
- `consumer_name`
- `workflow_instance_id`
- `processed_at`
- `result_hash`

唯一键：

- `event_id, consumer_name`

## outbox_events

Runtime 发布事件表。

关键列：

- `id`
- `aggregate_type`
- `aggregate_id`
- `event_type`
- `payload_json`
- `correlation_id`
- `causation_id`
- `status`
- `available_at`
- `published_at`
- `created_at`

## command_idempotency

保存 pause/resume/start/complete task 等 command 的幂等结果。

关键列：

- `idempotency_key`
- `command_type`
- `target_id`
- `request_hash`
- `response_json`
- `created_at`
- `expires_at`
