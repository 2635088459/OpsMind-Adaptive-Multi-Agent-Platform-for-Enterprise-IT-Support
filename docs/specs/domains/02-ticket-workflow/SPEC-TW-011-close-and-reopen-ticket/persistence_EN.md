# SPEC-TW-011 — Persistence Design

## 1. Migration Strategy

The spec folder provides a reference migration:

```text
V011__close_and_reopen_ticket.sql
```

The real service migration should use the current sequence:

```text
services/ticket-workflow-service/src/main/resources/db/migration/V017__close_and_reopen_ticket.sql
```

Reason: the real `SPEC-TW-010` migration is expected to use `V016__resolve_ticket.sql`.

## 2. Ticket Table Fields

`ticket.tickets` needs new or confirmed fields:

- `closed_by VARCHAR(128)`;
- `closed_at TIMESTAMPTZ`;
- `close_reason_code VARCHAR(64)`;
- `reopen_count INTEGER NOT NULL DEFAULT 0`;
- `last_reopened_at TIMESTAMPTZ`;
- `last_reopened_by VARCHAR(128)`;
- `last_reopen_reason_code VARCHAR(64)`;
- resolution fields already present or added by SPEC-TW-010.

## 3. Close Update

Recommended conditional update:

```sql
UPDATE ticket.tickets
SET status = 'CLOSED',
    closed_at = :closed_at,
    closed_by = :closed_by,
    close_reason_code = :close_reason_code,
    auto_close_due_at = NULL,
    active_workflow_id = NULL,
    updated_at = :updated_at,
    version = version + 1
WHERE ticket_id = :ticket_id
  AND version = :expected_version
  AND status = 'RESOLVED'
```

Database CHECK constraints should ensure `CLOSED` has at least `resolved_at`, `closed_at`, `closed_by`, and `close_reason_code`.

## 4. Reopen Update

Reopen must, in one transaction:

1. lock the current ticket;
2. validate status `RESOLVED` or `CLOSED`;
3. archive the old resolution cycle;
4. insert a new `ticket.ticket_resolution_cycles` row with `cycle_status = ACTIVE`;
5. point the ticket to the new cycle;
6. clear current resolution/close fields;
7. increment `reopen_count`;
8. write history/timeline/audit/outbox/idempotency.

Recommended ticket update:

```sql
UPDATE ticket.tickets
SET status = 'IN_PROGRESS',
    current_resolution_cycle_id = :new_resolution_cycle_id,
    resolved_at = NULL,
    resolved_by = NULL,
    resolution_code = NULL,
    resolution_summary = NULL,
    auto_close_due_at = NULL,
    closed_at = NULL,
    closed_by = NULL,
    close_reason_code = NULL,
    last_reopened_at = :reopened_at,
    last_reopened_by = :reopened_by,
    last_reopen_reason_code = :reopen_reason_code,
    reopen_count = reopen_count + 1,
    updated_at = :updated_at,
    version = version + 1
WHERE ticket_id = :ticket_id
  AND version = :expected_version
  AND status IN ('RESOLVED', 'CLOSED')
```

## 5. Resolution Cycle

Close:

- current cycle moves from `RESOLVED` to `CLOSED`;
- store `closed_at`, `closed_by_type`, `closed_by_id`, and `close_reason_code`.

Reopen:

- old cycle keeps resolved/closed snapshots;
- old cycle records `reopened_at`, `reopened_by_type`, `reopened_by_id`, and `reopen_reason_code`;
- new cycle uses `cycle_number = previous + 1` and status `ACTIVE`.

## 6. Status History

Close:

```text
transition_id = SM-011
reason_code = TICKET_CLOSED
from_status = RESOLVED
to_status = CLOSED
```

Reopen:

```text
transition_id = SM-012 or SM-013
reason_code = TICKET_REOPENED
from_status = RESOLVED or CLOSED
to_status = IN_PROGRESS
```

## 7. Constraints and Indexes

Recommended:

```sql
CREATE INDEX ix_tickets_closed_at
    ON ticket.tickets (closed_at DESC)
    WHERE status = 'CLOSED';

CREATE INDEX ix_tickets_reopen_count
    ON ticket.tickets (reopen_count)
    WHERE reopen_count > 0;
```

CHECK constraints should prevent negative `reopen_count` and restrict close/reopen reason codes.
