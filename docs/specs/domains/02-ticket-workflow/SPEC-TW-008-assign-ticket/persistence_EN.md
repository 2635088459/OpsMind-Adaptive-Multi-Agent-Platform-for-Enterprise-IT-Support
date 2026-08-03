# SPEC-TW-008 — Persistence Design

## 1. Ticket Columns

The ticket aggregate requires:

| Column | Type | Meaning |
|---|---|---|
| `assignee_id` | UUID nullable | current owner |
| `assigned_at` | TIMESTAMPTZ nullable | current ownership start |
| `assigned_by` | UUID nullable | actor that set current owner |
| `version` | BIGINT | optimistic-lock version |

Identity ownership may live in another service; therefore the reference migration does not create a cross-service foreign key.

## 2. Assignment History

`ticket_assignment_history` is append-only and contains:

- tenant and ticket ID;
- action: `ASSIGNED`, `REASSIGNED`, or `UNASSIGNED`;
- previous and new assignee;
- previous and new status;
- actor, reason, timestamp;
- correlation and causation IDs;
- resulting ticket version.

## 3. Existing Shared Tables

The command also writes existing Phase 01/02/007 tables:

- `ticket_status_history` for assign/unassign state changes;
- `ticket_timeline_entries`;
- `audit_log`;
- `idempotency_records`;
- `outbox_events`.

Reassignment does not add a status-history row because status is unchanged.

## 4. Atomic Write Algorithm

```text
BEGIN
  lock/claim idempotency key
  load ticket scoped by tenant
  validate actor, assignee, state, and expected version
  UPDATE tickets ... WHERE version = expectedVersion
  INSERT ticket_assignment_history
  INSERT ticket_status_history       -- assign/unassign only
  INSERT ticket_timeline_entries
  INSERT audit_log
  INSERT outbox_events
  finalize idempotency response
COMMIT
```

Any error causes `ROLLBACK`.

## 5. Optimistic Update

```sql
UPDATE tickets
SET assignee_id = :assignee_id,
    assigned_at = :assigned_at,
    assigned_by = :assigned_by,
    status = :status,
    version = version + 1,
    updated_at = :occurred_at
WHERE id = :ticket_id
  AND tenant_id = :tenant_id
  AND version = :expected_version;
```

Exactly one row must be updated.

## 6. Query and Index Requirements

- current queue and assignee workload: `(tenant_id, support_queue_id, assignee_id, status)`;
- ownership history: `(tenant_id, ticket_id, occurred_at, id)`;
- actor investigations: `(tenant_id, actor_id, occurred_at)`.

## 7. Timeline and Audit

Requester-safe timeline metadata may contain display names or stable user IDs according to privacy policy, but not role claims or queue membership evidence. Audit stores internal decision metadata, old/new values, actor, policy result, correlation, and source channel.

## 8. Retention and Integrity

- ownership history and audit are immutable through application APIs;
- timestamps are UTC;
- tenant ID is required on every new row;
- history `resulting_version` must match the committed Ticket version;
- outbox partition/aggregate key is `ticketId`;
- deletion and retention follow the platform governance policy.

## 9. Migration Note

`V008__assign_ticket.sql` is a reference migration. Reconcile table names, enum strategy, existing columns, and migration version with the real Phase 01–007 schema before applying.
