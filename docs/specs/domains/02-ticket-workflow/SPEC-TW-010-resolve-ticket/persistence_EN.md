# SPEC-TW-010 — Persistence Design

## 1. Migration Strategy

The spec folder provides a reference migration:

```text
V010__resolve_ticket.sql
```

The real service migration should use the current Flyway sequence:

```text
services/ticket-workflow-service/src/main/resources/db/migration/V016__resolve_ticket.sql
```

Reason: the codebase already uses `V015__transition_ticket_status.sql`.

## 2. Ticket Table Changes

`ticket.tickets` needs new or confirmed fields:

- `resolution_code VARCHAR(64)`;
- `resolution_summary TEXT`;
- `resolved_by VARCHAR(128)`;
- continue using existing `resolved_at TIMESTAMPTZ`;
- continue satisfying the earlier schema requirement that `RESOLVED` has `auto_close_due_at`, unless the same migration deliberately relaxes that constraint;
- clear `waiting_for_requester_since` and `approval_reference` on successful resolve;
- retain `current_support_user_id`.

## 3. Data Constraints

The database should defend these invariants:

- `RESOLVED` requires `resolved_at`, `resolved_by`, `resolution_code`, and `resolution_summary`;
- `RESOLVED` requires `current_support_user_id`;
- `RESOLVED` must not retain waiting metadata;
- `resolution_code` belongs to the controlled enum;
- trimmed `resolution_summary` length is 10 to 5000.
- if the `auto_close_due_at` constraint is retained, the resolve command must set it from local policy; the auto-close scheduler itself is outside SPEC-TW-010.

## 4. Resolution Cycle

Reuse existing `ticket.ticket_resolution_cycles`. Successful resolve completes the current cycle and stores:

- `resolved_at` / `completed_at`;
- `resolved_by` / `completed_by`;
- `resolution_code`;
- `resolution_summary` snapshot;
- resulting ticket version.

Column names should follow the existing table structure. If the table lacks snapshot fields, add them in `V016`.

The current `V003__create_ticket_resolution_cycles.sql` `ck_resolution_cycle_resolved` constraint also requires `root_cause_code` and `verification_id`. That belongs to the earlier frozen-state design and is not required input in the current Phase 03 / SPEC-TW-010 plan. `V016` must choose one of two paths:

- relax the CHECK so it requires only `resolved_at`, `resolution_code`, `resolution_summary`, and resolved actor data;
- or promote `rootCauseCode` and `verificationId` to required API fields and update this spec, OpenAPI, AsyncAPI, and the test plan.

This SPEC defaults to the first path.

## 5. Status History

Reuse `ticket.ticket_status_history`:

```text
transition_id = SM-010
reason_code = TICKET_RESOLVED
from_status = IN_PROGRESS
to_status = RESOLVED
```

`aggregate_version` equals the updated ticket version.

## 6. Timeline, Audit, Outbox

Every successful command writes, in the same transaction:

- requester-safe timeline item;
- internal audit record;
- outbox row with event type `ticket.resolved.v1`;
- finalized idempotency replay response.

Failed commands must not write success timeline, status history, or outbox events.

## 7. Repository Update

Recommended ticket update condition:

```sql
UPDATE ticket.tickets
SET status = 'RESOLVED',
    resolved_at = :resolved_at,
    resolved_by = :resolved_by,
    resolution_code = :resolution_code,
    resolution_summary = :resolution_summary,
    waiting_for_requester_since = NULL,
    approval_reference = NULL,
    updated_at = :updated_at,
    version = version + 1
WHERE ticket_id = :ticket_id
  AND version = :expected_version
  AND status = 'IN_PROGRESS'
  AND current_support_user_id IS NOT NULL
```

Resolution-cycle update must guard on `current_resolution_cycle_id` and incomplete status.

## 8. Indexes

Recommended:

```sql
CREATE INDEX ix_tickets_resolved_at
    ON ticket.tickets (resolved_at DESC)
    WHERE status = 'RESOLVED';

CREATE INDEX ix_tickets_resolution_code
    ON ticket.tickets (resolution_code)
    WHERE resolution_code IS NOT NULL;
```

These indexes support later close, auto-close, and operations analysis without implementing reporting.
