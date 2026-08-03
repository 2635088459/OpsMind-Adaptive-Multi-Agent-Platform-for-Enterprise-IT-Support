# SPEC-TW-009 — Persistence Design

## 1. Migration Strategy

The spec folder provides a reference migration:

```text
V009__transition_ticket_status.sql
```

The real service migration should use the current Flyway sequence:

```text
services/ticket-workflow-service/src/main/resources/db/migration/V015__transition_ticket_status.sql
```

Reason: the codebase already uses `V013__triage_ticket.sql` and `V014__assign_ticket.sql`.

## 2. Ticket Table Changes

`ticket.tickets` needs:

- add `IN_PROGRESS` to `ck_tickets_status`;
- add `waiting_for_requester_since TIMESTAMPTZ`;
- add `approval_reference VARCHAR(128)`;
- continue using `current_support_user_id` as ownership;
- continue using `support_queue_id/current_team_id` for queue authorization.

## 3. Data Constraints

The database should defend these invariants:

- `IN_PROGRESS`, `WAITING_FOR_USER`, and `WAITING_FOR_APPROVAL` require `current_support_user_id`;
- `WAITING_FOR_USER` requires `waiting_for_requester_since` and no `approval_reference`;
- `WAITING_FOR_APPROVAL` requires `approval_reference` and no `waiting_for_requester_since`;
- `IN_PROGRESS` does not retain active waiting metadata.

The Application Service must still clear metadata explicitly; database constraints are the final guardrail.

## 4. Status History

Reuse existing `ticket.ticket_status_history`:

```text
transition_id:
  SM-005 WORK_STARTED
  SM-006 WAITING_FOR_USER
  SM-007 WAITING_FOR_APPROVAL
  SM-008 WORK_RESUMED
  SM-009 WORK_RESUMED
```

Every successful transition writes one status-history row. `aggregate_version` equals the updated ticket version.

## 5. Timeline, Audit, Outbox

Every successful command writes, in the same transaction:

- requester-safe timeline item;
- internal audit record;
- outbox row with event type `ticket.status-changed.v1`;
- finalized idempotency replay response.

Failed commands must not write success timeline, status history, or outbox events.

## 6. Repository Update

Recommended update condition:

```sql
UPDATE ticket.tickets
SET status = :new_status,
    waiting_for_requester_since = :waiting_for_requester_since,
    approval_reference = :approval_reference,
    updated_at = :updated_at,
    version = version + 1
WHERE ticket_id = :ticket_id
  AND version = :expected_version
  AND status = :expected_status
  AND current_support_user_id IS NOT NULL
```

When zero rows are updated, the Application Service uses the guard's current version, status, and assignee to classify the error.

## 7. Indexes

Recommended:

```sql
CREATE INDEX ix_tickets_status_updated
    ON ticket.tickets (status, updated_at DESC);

CREATE INDEX ix_tickets_waiting_user
    ON ticket.tickets (waiting_for_requester_since)
    WHERE status = 'WAITING_FOR_USER';

CREATE INDEX ix_tickets_waiting_approval
    ON ticket.tickets (approval_reference)
    WHERE status = 'WAITING_FOR_APPROVAL';
```

These indexes support later queue, reminder, approval, and operations queries without implementing SLA or approval workflows.
