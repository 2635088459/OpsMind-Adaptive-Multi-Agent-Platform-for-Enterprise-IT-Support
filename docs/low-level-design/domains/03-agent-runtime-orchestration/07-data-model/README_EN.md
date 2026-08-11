# 07 Data Model

## Schema

Use a dedicated schema: `agent_runtime`. Runtime tables must not be mixed into Ticket Workflow tables.

## workflow_instances

Stores Workflow Instances.

Key columns:

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

Unique key:

- `ticket_id, ticket_cycle_id, workflow_type` where state is not terminal.

## agent_tasks

Stores Agent Tasks.

Key columns:

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

Indexes:

- `workflow_instance_id, state`
- `agent_role, state, claim_expires_at`

## checkpoints

Stores recovery snapshots.

Key columns:

- `id`
- `workflow_instance_id`
- `workflow_version`
- `checkpoint_type`
- `cursor`
- `payload_schema_version`
- `payload_json`
- `checksum`
- `created_at`

Indexes:

- `workflow_instance_id, created_at desc`
- `workflow_instance_id, workflow_version`

## tool_requests

Stores Tool Gateway requests.

Key columns:

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

Unique keys:

- `gateway_correlation_id`
- `agent_task_id, idempotency_key`

## processed_events

Consumed-event de-duplication table.

Key columns:

- `event_id`
- `event_type`
- `consumer_name`
- `workflow_instance_id`
- `processed_at`
- `result_hash`

Unique key:

- `event_id, consumer_name`

## outbox_events

Runtime published-event table.

Key columns:

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

Stores idempotent results for commands such as start, pause, resume, and complete task.

Key columns:

- `idempotency_key`
- `command_type`
- `target_id`
- `request_hash`
- `response_json`
- `created_at`
- `expires_at`
