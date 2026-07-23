# OpsMind Ticket Workflow — 07 Data Model

> **Domain:** Ticket & Business Workflow  
> **Document Type:** Low-Level PostgreSQL Data Model  
> **Version:** 1.0  
> **Status:** Proposed for Review  
> **Dependencies:** `01-domain-model_EN.md`, `02-business-invariants_EN.md`, `03-state-machine_EN.md`, `04-use-cases_EN.md`, `05-api-contracts_EN.md`, `06-event-contracts_EN.md`  
> **Database:** PostgreSQL 18.x  
> **Migration:** Flyway  
> **Recommended Path:** `System Design/Lower Structure Design_1.0/02-Ticket-Workflow/07-data-model_EN.md`

---

## 1. Purpose

This document maps the Ticket Workflow domain model and business rules into a PostgreSQL physical data model.

It freezes:

- PostgreSQL schema
- Table ownership
- Primary and foreign keys
- Column types
- Nullability
- Check constraints
- Unique constraints
- Partial unique indexes
- Query indexes
- Optimistic locking
- Append-only history
- Resolution cycles
- SLA cycles
- Pending actions
- User-input requests
- Transactional outbox
- Processed-event store
- API idempotency store
- PII classification
- Retention
- Flyway migration order

The DDL is a design-level draft. The implementation should not materially diverge without review.

---

# 2. Data Ownership

Ticket Workflow exclusively owns:

```text
ticket.*
```

Only `ticket-workflow-service` may write this schema.

Other services never directly insert, update, or delete Ticket data.

Cross-domain relationships store external identifiers only:

```text
workflow_id
approval_id
tool_execution_id
verification_id
```

No database foreign keys cross service boundaries.

---

# 3. Schema

```sql
CREATE SCHEMA IF NOT EXISTS ticket;
```

Recommended database roles:

```text
ticket_app
ticket_migration
ticket_readonly
```

- `ticket_migration`: DDL
- `ticket_app`: business DML
- `ticket_readonly`: controlled read access
- Other services receive no write permission

---

# 4. Physical Modeling Decisions

## DM-001 Internal IDs Use PostgreSQL `uuid`

Application-generated UUIDv7 or another ordered UUID is recommended.

Human-readable IDs use:

```text
display_id VARCHAR(32)
```

Example:

```text
INC-2048
```

## DM-002 Enums Use `VARCHAR + CHECK`

The MVP does not use PostgreSQL ENUM types.

This simplifies migrations, rollback, and alignment with Java and JSON contracts.

## DM-003 Time Uses `TIMESTAMPTZ`

All business timestamps are written as UTC.

## DM-004 Core Query Fields Are Relational Columns

Status, priority, category, requester, workflow, assignment, timestamps, and version are not hidden in JSONB.

JSONB is limited to event payloads, headers, and small non-core metadata.

## DM-005 Ordinary Soft Delete Is Not Used for History

Tickets, messages, history, cycles, and audit records are not hidden with a normal `deleted` flag.

## DM-006 Timeline Is Not a Source-of-Truth Table

The MVP composes Timeline from authoritative tables rather than maintaining a separate mutable timeline source.

---

# 5. Table Catalog

| Table | Type | Responsibility |
|---|---|---|
| `tickets` | Aggregate Snapshot | Current Ticket state |
| `ticket_messages` | Aggregate | User, support, and agent messages |
| `ticket_status_history` | Append-only | Lifecycle transitions |
| `ticket_category_history` | Append-only | Classification changes |
| `ticket_assignment_history` | Append-only | Assignment changes |
| `ticket_user_input_requests` | Lifecycle Record | WAITING_FOR_USER request |
| `ticket_pending_actions` | Aggregate Child / History | Action, approval, and execution refs |
| `ticket_resolution_cycles` | Cycle Record | Resolution and reopen cycles |
| `ticket_sla_cycles` | Aggregate | SLA cycles |
| `ticket_escalation_history` | Append-only | Escalation history |
| `ticket_automation_failures` | Append-only | Automation failures |
| `outbox_events` | Infrastructure | Transactional outbox |
| `processed_events` | Infrastructure | Consumer idempotency |
| `idempotency_records` | Infrastructure | HTTP command idempotency |

---

# 6. `ticket.tickets`

## Purpose

Stores the current Ticket aggregate snapshot, not its complete history.

## Draft DDL

```sql
CREATE TABLE ticket.tickets (
    ticket_id UUID PRIMARY KEY,
    display_id VARCHAR(32) NOT NULL,
    requester_id VARCHAR(128) NOT NULL,
    title VARCHAR(200) NOT NULL,
    initial_description TEXT NOT NULL,
    source VARCHAR(32) NOT NULL,
    application_code VARCHAR(64) NOT NULL,
    category VARCHAR(64),
    subcategory VARCHAR(64),
    priority VARCHAR(16) NOT NULL DEFAULT 'UNASSIGNED',
    status VARCHAR(32) NOT NULL,
    current_team_id VARCHAR(64),
    current_support_user_id VARCHAR(128),
    active_workflow_id VARCHAR(64),
    current_resolution_cycle_id UUID NOT NULL,
    auto_close_due_at TIMESTAMPTZ,
    resolved_at TIMESTAMPTZ,
    closed_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    cancel_reason_code VARCHAR(64),
    close_reason_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_by_type VARCHAR(32) NOT NULL,
    created_by_id VARCHAR(128) NOT NULL,

    CONSTRAINT uq_tickets_display_id UNIQUE (display_id),

    CONSTRAINT ck_tickets_title_not_blank
        CHECK (char_length(btrim(title)) BETWEEN 1 AND 200),

    CONSTRAINT ck_tickets_description_not_blank
        CHECK (char_length(btrim(initial_description)) BETWEEN 1 AND 10000),

    CONSTRAINT ck_tickets_source
        CHECK (source IN ('PORTAL', 'EMAIL', 'API', 'SYSTEM')),

    CONSTRAINT ck_tickets_priority
        CHECK (priority IN ('UNASSIGNED', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),

    CONSTRAINT ck_tickets_status
        CHECK (status IN (
            'NEW',
            'TRIAGING',
            'INVESTIGATING',
            'WAITING_FOR_USER',
            'WAITING_FOR_APPROVAL',
            'EXECUTING',
            'VERIFYING',
            'RESOLVED',
            'CLOSED',
            'ESCALATED',
            'FAILED',
            'CANCELLED'
        )),

    CONSTRAINT ck_tickets_version_nonnegative
        CHECK (version >= 0),

    CONSTRAINT ck_tickets_created_updated
        CHECK (updated_at >= created_at),

    CONSTRAINT ck_tickets_support_user_requires_team
        CHECK (
            current_support_user_id IS NULL
            OR current_team_id IS NOT NULL
        ),

    CONSTRAINT ck_tickets_resolved_fields
        CHECK (
            status <> 'RESOLVED'
            OR (
                resolved_at IS NOT NULL
                AND auto_close_due_at IS NOT NULL
            )
        ),

    CONSTRAINT ck_tickets_closed_fields
        CHECK (
            status <> 'CLOSED'
            OR (
                resolved_at IS NOT NULL
                AND closed_at IS NOT NULL
                AND close_reason_code IS NOT NULL
                AND active_workflow_id IS NULL
            )
        ),

    CONSTRAINT ck_tickets_cancelled_fields
        CHECK (
            status <> 'CANCELLED'
            OR (
                cancelled_at IS NOT NULL
                AND cancel_reason_code IS NOT NULL
            )
        )
);
```

## Core Indexes

```sql
CREATE INDEX ix_tickets_requester_created
    ON ticket.tickets (requester_id, created_at DESC, ticket_id DESC);

CREATE INDEX ix_tickets_status_updated
    ON ticket.tickets (status, updated_at DESC, ticket_id DESC);

CREATE INDEX ix_tickets_queue_status_priority
    ON ticket.tickets (
        current_team_id,
        status,
        priority,
        updated_at DESC
    );

CREATE INDEX ix_tickets_assignee_status
    ON ticket.tickets (
        current_support_user_id,
        status,
        updated_at DESC
    )
    WHERE current_support_user_id IS NOT NULL;

CREATE INDEX ix_tickets_active_workflow
    ON ticket.tickets (active_workflow_id)
    WHERE active_workflow_id IS NOT NULL;

CREATE INDEX ix_tickets_auto_close_due
    ON ticket.tickets (auto_close_due_at, ticket_id)
    WHERE status = 'RESOLVED'
      AND auto_close_due_at IS NOT NULL;

CREATE INDEX ix_tickets_application_status
    ON ticket.tickets (application_code, status, created_at DESC);
```

## Optimistic Update

```sql
UPDATE ticket.tickets
SET
    status = :new_status,
    updated_at = :updated_at,
    version = version + 1
WHERE ticket_id = :ticket_id
  AND version = :expected_version;
```

Exactly one row must be updated.

---

# 7. `ticket.ticket_messages`

Messages are an independent aggregate and are immutable after creation except for controlled redaction or visibility correction.

```sql
CREATE TABLE ticket.ticket_messages (
    message_id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL,
    author_type VARCHAR(32) NOT NULL,
    author_id VARCHAR(128) NOT NULL,
    message_type VARCHAR(32) NOT NULL,
    visibility VARCHAR(32) NOT NULL,
    body TEXT NOT NULL,
    reply_to_message_id UUID,
    attachment_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    redacted_at TIMESTAMPTZ,
    redaction_reason_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_ticket_messages_ticket
        FOREIGN KEY (ticket_id)
        REFERENCES ticket.tickets(ticket_id),

    CONSTRAINT fk_ticket_messages_reply
        FOREIGN KEY (reply_to_message_id)
        REFERENCES ticket.ticket_messages(message_id),

    CONSTRAINT ck_ticket_messages_body
        CHECK (char_length(btrim(body)) BETWEEN 1 AND 20000),

    CONSTRAINT ck_ticket_messages_author_type
        CHECK (author_type IN (
            'EMPLOYEE',
            'IT_SUPPORT',
            'IT_ADMIN',
            'SYSTEM',
            'AGENT',
            'SERVICE'
        )),

    CONSTRAINT ck_ticket_messages_type
        CHECK (message_type IN (
            'USER_MESSAGE',
            'SUPPORT_MESSAGE',
            'SYSTEM_MESSAGE',
            'AGENT_QUESTION',
            'AGENT_SUMMARY',
            'RESOLUTION_INSTRUCTION'
        )),

    CONSTRAINT ck_ticket_messages_visibility
        CHECK (visibility IN (
            'REQUESTER_VISIBLE',
            'INTERNAL_SUPPORT_ONLY',
            'AUDIT_ONLY'
        )),

    CONSTRAINT ck_ticket_messages_attachments_array
        CHECK (jsonb_typeof(attachment_ids) = 'array'),

    CONSTRAINT ck_ticket_messages_metadata_object
        CHECK (jsonb_typeof(metadata) = 'object')
);
```

Indexes:

```sql
CREATE INDEX ix_ticket_messages_ticket_created
    ON ticket.ticket_messages (ticket_id, created_at ASC, message_id ASC);

CREATE INDEX ix_ticket_messages_ticket_visibility_created
    ON ticket.ticket_messages (
        ticket_id,
        visibility,
        created_at ASC
    );

CREATE INDEX ix_ticket_messages_reply_to
    ON ticket.ticket_messages (reply_to_message_id)
    WHERE reply_to_message_id IS NOT NULL;
```

---

# 8. `ticket.ticket_status_history`

Append-only lifecycle history:

```sql
CREATE TABLE ticket.ticket_status_history (
    history_id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL,
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    transition_id VARCHAR(16) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    actor_type VARCHAR(32) NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    source_command_id VARCHAR(128),
    source_event_id VARCHAR(64),
    workflow_id VARCHAR(64),
    aggregate_version BIGINT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_ticket_status_history_ticket
        FOREIGN KEY (ticket_id)
        REFERENCES ticket.tickets(ticket_id),

    CONSTRAINT uq_ticket_status_history_version
        UNIQUE (ticket_id, aggregate_version),

    CONSTRAINT ck_ticket_status_history_version
        CHECK (aggregate_version >= 0)
);
```

Indexes:

```sql
CREATE INDEX ix_ticket_status_history_ticket_time
    ON ticket.ticket_status_history (
        ticket_id,
        occurred_at ASC,
        history_id ASC
    );

CREATE INDEX ix_ticket_status_history_source_event
    ON ticket.ticket_status_history (source_event_id)
    WHERE source_event_id IS NOT NULL;
```

---

# 9. `ticket.ticket_category_history`

```sql
CREATE TABLE ticket.ticket_category_history (
    category_history_id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL,
    old_category VARCHAR(64),
    old_subcategory VARCHAR(64),
    new_category VARCHAR(64) NOT NULL,
    new_subcategory VARCHAR(64),
    priority VARCHAR(16) NOT NULL,
    confidence NUMERIC(5,4),
    classification_source VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64),
    workflow_id VARCHAR(64),
    source_event_id VARCHAR(64),
    aggregate_version BIGINT NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_ticket_category_history_ticket
        FOREIGN KEY (ticket_id)
        REFERENCES ticket.tickets(ticket_id),

    CONSTRAINT ck_ticket_category_confidence
        CHECK (
            confidence IS NULL
            OR confidence BETWEEN 0 AND 1
        )
);
```

---

# 10. `ticket.ticket_assignment_history`

```sql
CREATE TABLE ticket.ticket_assignment_history (
    assignment_history_id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL,
    old_team_id VARCHAR(64),
    old_support_user_id VARCHAR(128),
    new_team_id VARCHAR(64),
    new_support_user_id VARCHAR(128),
    assigned_by_type VARCHAR(32) NOT NULL,
    assigned_by_id VARCHAR(128) NOT NULL,
    reason_code VARCHAR(64),
    aggregate_version BIGINT NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_ticket_assignment_history_ticket
        FOREIGN KEY (ticket_id)
        REFERENCES ticket.tickets(ticket_id),

    CONSTRAINT ck_assignment_support_user_requires_team
        CHECK (
            new_support_user_id IS NULL
            OR new_team_id IS NOT NULL
        )
);
```

---

# 11. `ticket.ticket_user_input_requests`

Stores the request reference for WAITING_FOR_USER.

```sql
CREATE TABLE ticket.ticket_user_input_requests (
    user_input_request_id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL,
    workflow_id VARCHAR(64) NOT NULL,
    request_key VARCHAR(64) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    request_message_id UUID NOT NULL,
    response_message_id UUID,
    resume_status VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    answered_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    expired_at TIMESTAMPTZ,

    CONSTRAINT fk_user_input_request_ticket
        FOREIGN KEY (ticket_id)
        REFERENCES ticket.tickets(ticket_id),

    CONSTRAINT fk_user_input_request_message
        FOREIGN KEY (request_message_id)
        REFERENCES ticket.ticket_messages(message_id),

    CONSTRAINT fk_user_input_response_message
        FOREIGN KEY (response_message_id)
        REFERENCES ticket.ticket_messages(message_id),

    CONSTRAINT uq_user_input_request_key
        UNIQUE (ticket_id, request_key),

    CONSTRAINT ck_user_input_resume_status
        CHECK (resume_status IN ('TRIAGING', 'INVESTIGATING')),

    CONSTRAINT ck_user_input_status
        CHECK (status IN ('OPEN', 'ANSWERED', 'CANCELLED', 'EXPIRED')),

    CONSTRAINT ck_user_input_answered
        CHECK (
            status <> 'ANSWERED'
            OR (
                response_message_id IS NOT NULL
                AND answered_at IS NOT NULL
            )
        )
);
```

At most one open request per Ticket:

```sql
CREATE UNIQUE INDEX uq_ticket_one_open_user_request
    ON ticket.ticket_user_input_requests (ticket_id)
    WHERE status = 'OPEN';
```

---

# 12. `ticket.ticket_pending_actions`

Stores pending-action, approval, policy, and tool-execution references while preserving history.

```sql
CREATE TABLE ticket.ticket_pending_actions (
    pending_action_id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL,
    workflow_id VARCHAR(64) NOT NULL,
    action_id VARCHAR(64) NOT NULL,
    action_type VARCHAR(64) NOT NULL,
    risk_level VARCHAR(16) NOT NULL,
    approval_id VARCHAR(64),
    policy_decision_id VARCHAR(64),
    policy_decision VARCHAR(32),
    tool_execution_id VARCHAR(64),
    idempotency_key VARCHAR(128),
    status VARCHAR(24) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    authorized_at TIMESTAMPTZ,
    consumed_at TIMESTAMPTZ,
    invalidated_at TIMESTAMPTZ,
    invalidation_reason_code VARCHAR(64),

    CONSTRAINT fk_pending_action_ticket
        FOREIGN KEY (ticket_id)
        REFERENCES ticket.tickets(ticket_id),

    CONSTRAINT uq_pending_action_business_id
        UNIQUE (ticket_id, action_id),

    CONSTRAINT uq_pending_action_tool_execution
        UNIQUE (tool_execution_id),

    CONSTRAINT ck_pending_action_risk
        CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),

    CONSTRAINT ck_pending_action_policy_decision
        CHECK (
            policy_decision IS NULL
            OR policy_decision IN (
                'APPROVED',
                'AUTO_APPROVED',
                'REJECTED',
                'EXPIRED'
            )
        ),

    CONSTRAINT ck_pending_action_status
        CHECK (status IN (
            'PENDING_APPROVAL',
            'AUTHORIZED',
            'EXECUTING',
            'CONSUMED',
            'REJECTED',
            'EXPIRED',
            'INVALIDATED'
        ))
);
```

At most one active pending action:

```sql
CREATE UNIQUE INDEX uq_ticket_one_active_pending_action
    ON ticket.ticket_pending_actions (ticket_id)
    WHERE status IN (
        'PENDING_APPROVAL',
        'AUTHORIZED',
        'EXECUTING'
    );
```

---

# 13. `ticket.ticket_resolution_cycles`

Preserves each processing cycle after creation or reopen.

```sql
CREATE TABLE ticket.ticket_resolution_cycles (
    resolution_cycle_id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL,
    cycle_number INTEGER NOT NULL,
    workflow_id VARCHAR(64),
    sla_cycle_id UUID,
    cycle_status VARCHAR(24) NOT NULL,
    opened_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    closed_at TIMESTAMPTZ,
    reopened_at TIMESTAMPTZ,
    reopen_reason_code VARCHAR(64),
    reopened_by_type VARCHAR(32),
    reopened_by_id VARCHAR(128),
    resolution_code VARCHAR(64),
    root_cause_code VARCHAR(64),
    resolution_summary TEXT,
    verification_id VARCHAR(64),
    resolution_attempt_id VARCHAR(64),
    resolved_by_type VARCHAR(32),
    resolved_by_id VARCHAR(128),
    close_reason_code VARCHAR(64),
    closed_by_type VARCHAR(32),
    closed_by_id VARCHAR(128),

    CONSTRAINT fk_resolution_cycle_ticket
        FOREIGN KEY (ticket_id)
        REFERENCES ticket.tickets(ticket_id),

    CONSTRAINT uq_resolution_cycle_number
        UNIQUE (ticket_id, cycle_number),

    CONSTRAINT ck_resolution_cycle_number
        CHECK (cycle_number >= 1),

    CONSTRAINT ck_resolution_cycle_status
        CHECK (cycle_status IN (
            'ACTIVE',
            'RESOLVED',
            'CLOSED',
            'REOPENED',
            'CANCELLED'
        )),

    CONSTRAINT ck_resolution_cycle_resolved
        CHECK (
            cycle_status NOT IN ('RESOLVED', 'CLOSED', 'REOPENED')
            OR (
                resolved_at IS NOT NULL
                AND resolution_code IS NOT NULL
                AND root_cause_code IS NOT NULL
                AND verification_id IS NOT NULL
            )
        )
);
```

Only one ACTIVE cycle:

```sql
CREATE UNIQUE INDEX uq_ticket_one_active_resolution_cycle
    ON ticket.ticket_resolution_cycles (ticket_id)
    WHERE cycle_status = 'ACTIVE';
```

The Ticket row identifies the current cycle even after it becomes RESOLVED.

---

# 14. `ticket.ticket_sla_cycles`

Independent SLA aggregate:

```sql
CREATE TABLE ticket.ticket_sla_cycles (
    sla_cycle_id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL,
    resolution_cycle_id UUID NOT NULL,
    policy_id VARCHAR(64) NOT NULL,
    cycle_number INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL,
    response_due_at TIMESTAMPTZ,
    resolution_due_at TIMESTAMPTZ,
    paused_at TIMESTAMPTZ,
    accumulated_paused_seconds BIGINT NOT NULL DEFAULT 0,
    breached_at TIMESTAMPTZ,
    met_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT fk_sla_ticket
        FOREIGN KEY (ticket_id)
        REFERENCES ticket.tickets(ticket_id),

    CONSTRAINT fk_sla_resolution_cycle
        FOREIGN KEY (resolution_cycle_id)
        REFERENCES ticket.ticket_resolution_cycles(resolution_cycle_id),

    CONSTRAINT uq_sla_cycle_number
        UNIQUE (ticket_id, cycle_number),

    CONSTRAINT uq_sla_resolution_cycle
        UNIQUE (resolution_cycle_id),

    CONSTRAINT ck_sla_status
        CHECK (status IN (
            'ACTIVE',
            'PAUSED',
            'MET',
            'BREACHED',
            'CANCELLED'
        )),

    CONSTRAINT ck_sla_pause_seconds
        CHECK (accumulated_paused_seconds >= 0),

    CONSTRAINT ck_sla_time_order
        CHECK (
            resolution_due_at IS NULL
            OR resolution_due_at >= created_at
        ),

    CONSTRAINT ck_sla_version
        CHECK (version >= 0)
);
```

At most one current SLA:

```sql
CREATE UNIQUE INDEX uq_ticket_one_active_sla_cycle
    ON ticket.ticket_sla_cycles (ticket_id)
    WHERE status IN ('ACTIVE', 'PAUSED', 'BREACHED');
```

---

# 15. `ticket.ticket_escalation_history`

```sql
CREATE TABLE ticket.ticket_escalation_history (
    escalation_id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL,
    resolution_cycle_id UUID NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_id VARCHAR(128) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    comment TEXT,
    evidence_reference_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    automation_restricted BOOLEAN NOT NULL DEFAULT TRUE,
    escalated_by_type VARCHAR(32) NOT NULL,
    escalated_by_id VARCHAR(128) NOT NULL,
    source_event_id VARCHAR(64),
    aggregate_version BIGINT NOT NULL,
    escalated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_escalation_ticket
        FOREIGN KEY (ticket_id)
        REFERENCES ticket.tickets(ticket_id),

    CONSTRAINT fk_escalation_cycle
        FOREIGN KEY (resolution_cycle_id)
        REFERENCES ticket.ticket_resolution_cycles(resolution_cycle_id),

    CONSTRAINT ck_escalation_evidence_array
        CHECK (jsonb_typeof(evidence_reference_ids) = 'array')
);
```

---

# 16. `ticket.ticket_automation_failures`

```sql
CREATE TABLE ticket.ticket_automation_failures (
    failure_record_id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL,
    resolution_cycle_id UUID NOT NULL,
    workflow_id VARCHAR(64),
    failure_reference_id VARCHAR(64) NOT NULL,
    failure_category VARCHAR(64) NOT NULL,
    error_code VARCHAR(64),
    retryable BOOLEAN NOT NULL,
    retry_count INTEGER NOT NULL DEFAULT 0,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    source_event_id VARCHAR(64),
    aggregate_version BIGINT NOT NULL,
    failed_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_automation_failure_ticket
        FOREIGN KEY (ticket_id)
        REFERENCES ticket.tickets(ticket_id),

    CONSTRAINT fk_automation_failure_cycle
        FOREIGN KEY (resolution_cycle_id)
        REFERENCES ticket.ticket_resolution_cycles(resolution_cycle_id),

    CONSTRAINT uq_automation_failure_reference
        UNIQUE (failure_reference_id),

    CONSTRAINT ck_automation_failure_retry_count
        CHECK (retry_count >= 0),

    CONSTRAINT ck_automation_failure_details
        CHECK (jsonb_typeof(details) = 'object')
);
```

The details field excludes complete stack traces, prompts, and secrets.

---

# 17. `ticket.outbox_events`

Stores integration events in the same transaction as business state.

```sql
CREATE TABLE ticket.outbox_events (
    outbox_id UUID PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    event_version VARCHAR(16) NOT NULL,
    routing_key VARCHAR(160) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    aggregate_version BIGINT,
    ticket_id UUID NOT NULL,
    workflow_id VARCHAR(64),
    trace_id VARCHAR(64) NOT NULL,
    correlation_id VARCHAR(128) NOT NULL,
    causation_id VARCHAR(128),
    data_classification VARCHAR(16) NOT NULL,
    payload JSONB NOT NULL,
    headers JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,
    available_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    publish_attempts INTEGER NOT NULL DEFAULT 0,
    last_publish_error_code VARCHAR(64),
    last_publish_error_at TIMESTAMPTZ,
    locked_by VARCHAR(128),
    locked_at TIMESTAMPTZ,

    CONSTRAINT uq_outbox_event_id UNIQUE (event_id),

    CONSTRAINT fk_outbox_ticket
        FOREIGN KEY (ticket_id)
        REFERENCES ticket.tickets(ticket_id),

    CONSTRAINT ck_outbox_classification
        CHECK (data_classification IN (
            'PUBLIC',
            'INTERNAL',
            'SENSITIVE'
        )),

    CONSTRAINT ck_outbox_payload_object
        CHECK (jsonb_typeof(payload) = 'object'),

    CONSTRAINT ck_outbox_headers_object
        CHECK (jsonb_typeof(headers) = 'object'),

    CONSTRAINT ck_outbox_publish_attempts
        CHECK (publish_attempts >= 0)
);
```

Publisher indexes:

```sql
CREATE INDEX ix_outbox_unpublished_available
    ON ticket.outbox_events (
        available_at,
        created_at,
        outbox_id
    )
    WHERE published_at IS NULL;

CREATE INDEX ix_outbox_locked
    ON ticket.outbox_events (locked_at)
    WHERE published_at IS NULL
      AND locked_at IS NOT NULL;

CREATE INDEX ix_outbox_ticket_created
    ON ticket.outbox_events (
        ticket_id,
        created_at ASC
    );
```

Claim query:

```sql
SELECT *
FROM ticket.outbox_events
WHERE published_at IS NULL
  AND available_at <= now()
  AND (
      locked_at IS NULL
      OR locked_at < now() - interval '5 minutes'
  )
ORDER BY created_at, outbox_id
FOR UPDATE SKIP LOCKED
LIMIT :batch_size;
```

---

# 18. `ticket.processed_events`

Supports consumer idempotency and replay audit.

```sql
CREATE TABLE ticket.processed_events (
    consumer_name VARCHAR(128) NOT NULL,
    event_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    event_version VARCHAR(16) NOT NULL,
    ticket_id UUID NOT NULL,
    workflow_id VARCHAR(64),
    payload_hash CHAR(64) NOT NULL,
    processing_result VARCHAR(32) NOT NULL,
    aggregate_version_after BIGINT,
    first_received_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    error_code VARCHAR(64),

    PRIMARY KEY (consumer_name, event_id),

    CONSTRAINT fk_processed_event_ticket
        FOREIGN KEY (ticket_id)
        REFERENCES ticket.tickets(ticket_id),

    CONSTRAINT ck_processed_event_result
        CHECK (processing_result IN (
            'APPLIED',
            'DUPLICATE',
            'STALE',
            'REJECTED_BUSINESS_RULE'
        ))
);
```

The same EventId with a different payload hash triggers:

```text
EVENT_ID_REUSED_WITH_DIFFERENT_PAYLOAD
```

---

# 19. `ticket.idempotency_records`

Protects HTTP commands such as create, message, cancel, reopen, and confirm.

```sql
CREATE TABLE ticket.idempotency_records (
    idempotency_record_id UUID PRIMARY KEY,
    actor_scope VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    operation_id VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    resource_type VARCHAR(64),
    resource_id VARCHAR(64),
    response_status INTEGER,
    response_body JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_idempotency_scope_key
        UNIQUE (actor_scope, idempotency_key),

    CONSTRAINT ck_idempotency_status
        CHECK (status IN (
            'IN_PROGRESS',
            'COMPLETED',
            'FAILED_RETRYABLE',
            'FAILED_FINAL'
        )),

    CONSTRAINT ck_idempotency_response_body
        CHECK (
            response_body IS NULL
            OR jsonb_typeof(response_body) = 'object'
        )
);
```

Recommended TTL:

```text
24 hours
```

Expired records are deleted only when not `IN_PROGRESS`.

---

# 20. Foreign-Key Strategy

Internal schema foreign keys are allowed.

Cross-service identifiers do not receive database foreign keys:

```text
workflow_id
approval_id
tool_execution_id
verification_id
requester_id
support_user_id
team_id
```

This preserves service autonomy.

---

# 21. Transaction Mapping

## Create Ticket

```text
INSERT tickets
INSERT resolution_cycles
INSERT sla_cycles
INSERT status_history
INSERT outbox_events
INSERT or UPDATE idempotency_records
COMMIT
```

## State Transition

```text
UPDATE tickets using expected version
INSERT status history
INSERT optional domain history
INSERT outbox events
INSERT processed event when event-driven
COMMIT
```

## User Reply Resume

```text
INSERT message
UPDATE user-input request
UPDATE SLA
UPDATE Ticket
INSERT history
INSERT outbox
UPDATE idempotency record
COMMIT
```

## Verification Success

```text
UPDATE resolution cycle
UPDATE SLA cycle
UPDATE Ticket
INSERT history
INSERT ticket.resolved outbox event
INSERT processed event
COMMIT
```

---

# 22. Invariant-to-Constraint Mapping

| Rule | Database Support |
|---|---|
| Unique Display ID | Unique constraint |
| Nonnegative version | Check |
| One open user-input request | Partial unique index |
| One active pending action | Partial unique index |
| One active SLA cycle | Partial unique index |
| Unique cycle number | Unique constraint |
| Process event once | Composite primary key |
| Publish event ID once | Unique event ID |
| Unique idempotency key per scope | Unique constraint |
| Unique history aggregate version | Unique constraint |
| Support user requires team | Check |
| CLOSED requires close data | Check |
| CANCELLED requires cancellation data | Check |

Database constraints are a final defense and do not replace domain guards.

---

# 23. Query Models

## Employee Ticket List

Uses:

```text
tickets(requester_id, created_at DESC, ticket_id DESC)
```

## Support Queue

Uses:

```text
tickets(current_team_id, status, priority, updated_at DESC)
```

## Auto-close

Uses the partial index on RESOLVED tickets with `auto_close_due_at`.

## Timeline

Reads authoritative history, message, escalation, resolution, and SLA tables, merges them in the query layer, and applies role-based filtering.

The MVP does not require Elasticsearch.

---

# 24. Cursor Pagination

Cursor content:

```json
{
  "createdAt": "2026-07-23T16:30:00Z",
  "ticketId": "01J..."
}
```

The cursor is opaque to clients.

---

# 25. PII Classification

| Data | Classification |
|---|---|
| requester ID | Sensitive |
| title | Sensitive |
| description | Sensitive |
| message body | Sensitive |
| actor identifiers | Sensitive |
| resolution summary | Sensitive |
| outbox payload | Envelope classification |
| payload hash | Internal |
| status and category | Internal |
| Ticket and Display IDs | Internal |

PII is never used as a metric label. Logs exclude message bodies and descriptions.

---

# 26. Encryption and Secrets

At-rest encryption is provided by encrypted PostgreSQL storage.

In-transit access uses TLS.

The MVP does not apply per-column application encryption.

Secrets never enter this schema:

```text
password
access token
refresh token
API key
private key
raw Duo administrator credential
```

---

# 27. Retention

| Data | Recommended Retention |
|---|---|
| Ticket core | 2 years |
| Messages | 2 years |
| Lifecycle history | 2 years |
| Resolution and SLA cycles | 2 years |
| Published outbox rows | 30 days |
| Processed events | 90 days |
| Idempotency records | 24 hours after expiry |
| Failure details | 90 days |
| DLQ messages | 30 days with manual handling |

Deletion jobs use small batches, short transactions, and legal-hold protection.

---

# 28. Partitioning Decision

The MVP does not partition business tables.

Future candidates include monthly partitions for outbox, processed events, and status history after significant scale.

---

# 29. Flyway Migration Order

```text
V001__create_ticket_schema.sql
V002__create_tickets.sql
V003__create_resolution_cycles.sql
V004__create_sla_cycles.sql
V005__create_ticket_messages.sql
V006__create_status_and_domain_history.sql
V007__create_user_input_requests.sql
V008__create_pending_actions.sql
V009__create_outbox_events.sql
V010__create_processed_events.sql
V011__create_idempotency_records.sql
V012__create_indexes.sql
V013__grant_ticket_permissions.sql
```

Because Ticket and Resolution Cycle reference each other conceptually, the MVP keeps `current_resolution_cycle_id` as a controlled application reference without a reverse foreign key.

---

# 30. JPA Mapping

Recommended persistence separation:

```text
Domain Ticket
TicketJpaEntity
TicketPersistenceMapper
```

`@Version` maps to `tickets.version`.

Query repositories may use JdbcTemplate or projections and do not need to load the complete aggregate.

---

# 31. Data Access Layers

```text
Domain Repository
→ TicketRepository

Infrastructure
→ JpaTicketRepository
→ TicketPersistenceAdapter
```

Query side:

```text
TicketQueryRepository
TicketTimelineQueryRepository
SupportQueueQueryRepository
```

---

# 32. Backup and Restore

The project must verify:

- PostgreSQL backup restores successfully
- Constraints and indexes survive
- Unpublished outbox events are preserved
- Processed-event state remains aligned
- Replayed broker events remain idempotent after restore

The MVP includes a documented `pg_dump` and restore drill.

---

# 33. Integrity Monitoring

Periodic checks detect:

```text
Ticket without current resolution cycle
RESOLVED Ticket without resolution data
CLOSED Ticket without closedAt
WAITING_FOR_APPROVAL without active pending action
WAITING_FOR_USER without open input request
Workflow and current cycle mismatch
Old unpublished outbox events
Processed-event and aggregate-version anomalies
```

Recommended metric:

```text
ticket_data_integrity_violation_total
```

---

# 34. Critical Integration Tests

```text
shouldCreateTicketWithResolutionAndSlaCycleAtomically
shouldEnforceUniqueDisplayId
shouldAllowOnlyOneOpenUserInputRequest
shouldAllowOnlyOneActivePendingAction
shouldAllowOnlyOneActiveSlaCycle
shouldRejectSupportUserWithoutTeam
shouldEnforceOptimisticLock
shouldRollbackHistoryWhenTicketUpdateFails
shouldRollbackOutboxWhenTicketUpdateFails
shouldRollbackTicketWhenOutboxInsertFails
shouldDeduplicateProcessedEvent
shouldDetectReusedEventIdWithDifferentPayload
shouldClaimOutboxRowsWithSkipLocked
shouldQueryRequesterTicketsWithCursor
shouldQuerySupportQueueUsingCompositeIndex
shouldPreservePreviousResolutionCycleAfterReopen
```

---

# 35. Rejected Data Models

- A single `tickets.payload JSONB` column
- Messages stored as a JSON array inside Ticket
- Status history loaded as an aggregate list
- Overwriting the previous resolution after reopen
- Redis as the business source of truth
- Other services writing the Ticket schema
- A database trigger implementing the complete state machine

The domain layer owns behavior; the database provides constraints and durability.

---

# 36. Acceptance Criteria

- [x] Schema and ownership defined
- [x] Ticket snapshot table defined
- [x] Message aggregate table defined
- [x] Status, category, and assignment history defined
- [x] User-input request defined
- [x] Pending action defined
- [x] Resolution cycle defined
- [x] SLA cycle defined
- [x] Escalation and failure records defined
- [x] Outbox defined
- [x] Processed-event store defined
- [x] Idempotency store defined
- [x] Keys, constraints, and partial indexes defined
- [x] Query indexes and cursor pagination defined
- [x] PII, retention, and encryption defined
- [x] Flyway migration order defined
- [x] JPA and repository mapping guidance defined
- [x] Integration tests defined

---

# 37. Next Step

Create:

```text
08-transaction-and-outbox_CN.md
08-transaction-and-outbox_EN.md
```

That document will define transaction boundaries, the Outbox Publisher lifecycle, publisher confirms, row claiming, retry, crash recovery, and atomicity across business state, history, processed events, and outgoing events.
