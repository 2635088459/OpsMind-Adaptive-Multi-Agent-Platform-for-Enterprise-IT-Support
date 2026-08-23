# 07 Data Model

## Database

MVP uses PostgreSQL for policy versions, policy decisions, approval requests, approval decisions, governance audit, outbox, and processed events.

## Tables

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

Unique key: `(policy_id, version)`.

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

Unique key: `(decision_key, input_hash)`.

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

Unique key: `(source_domain, source_request_id, request_key)`.

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

Unique key: `approval_request_id`, guaranteeing only one final decision per approval request.

### `governance_audit_records`

Records all policy/approval/override/admin actions.

### `outbox_events`

Publishes `approval.*`, `policy.*`, and `governance.*` events.

### `processed_events`

Deduplicates consumed external events.

## Indexes

- `policy_decisions(source_domain, source_request_id)`
- `approval_requests(status, expires_at)`
- `approval_requests(ticket_id)`
- `approval_requests(workflow_instance_id)`
- `approval_requests(tool_request_id)`
- `outbox_events(status, available_at)`
- `processed_events(event_id, consumer_name)`

