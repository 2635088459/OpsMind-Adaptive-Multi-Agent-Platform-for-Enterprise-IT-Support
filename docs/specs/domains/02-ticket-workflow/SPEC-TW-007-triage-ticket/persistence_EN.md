# SPEC-TW-007 — Persistence Design

## 1. Ticket Fields

Add or confirm the following fields on `tickets`:

| Column | Type | Nullability | Rule |
|---|---|---:|---|
| `category_id` | UUID | nullable before triage | Required when status is not `OPEN` |
| `subcategory_id` | UUID | nullable | Must belong to category |
| `priority` | VARCHAR(16) | nullable before triage | Four-value enum |
| `support_queue_id` | UUID | nullable before triage | Active queue at command time |
| `triaged_by` | UUID | nullable before triage | Authenticated actor |
| `triaged_at` | `TIMESTAMPTZ` | nullable before triage | UTC server time |
| `version` | BIGINT | not null | Incremented by optimistic update |

If Phase 01 already assigned a default priority, this migration must preserve it but triage must still explicitly confirm the final priority.

## 2. Catalog Tables

`ticket_categories` and `ticket_subcategories` provide tenant-scoped active catalogs. Historical tickets retain identifiers even if a catalog entry is later deactivated. Hard deletion of referenced catalog entries is prohibited.

## 3. Existing Cross-Cutting Tables

Reuse the existing Phase 01/02 tables where present:

- `ticket_status_history`;
- `ticket_timeline`;
- `audit_log`;
- `outbox_events`;
- `idempotency_records`;
- `support_queues`;
- queue membership/authorization tables.

Do not create separate triage-only history or outbox tables.

## 4. Required Writes

For one successful command:

| Store | Record |
|---|---|
| `tickets` | new triage fields, `TRIAGED`, version + 1 |
| `ticket_status_history` | `OPEN → TRIAGED`, operation `TRIAGE` |
| `ticket_timeline` | type `TICKET_TRIAGED`, visibility `INTERNAL` |
| `audit_log` | action `ticket.triage`, before/after approved fields |
| `outbox_events` | aggregate `TICKET`, event `ticket.triaged.v1` |
| `idempotency_records` | request hash, final response, ETag |

All writes share the same `occurred_at`, actor, tenant, and correlation ID.

## 5. Constraints and Indexes

- priority check constraint;
- category/subcategory parent foreign key;
- tenant-aware lookup indexes;
- partial index for triaged queue queries;
- unique outbox `event_id`;
- unique idempotency scope;
- status/triage field consistency check where compatible with the existing lifecycle migration.

## 6. Migration Safety

The reference migration assumes PostgreSQL and Flyway naming. Before copying it into the service:

1. reconcile table/column names with earlier migrations;
2. remove statements already introduced by Phase 01/02;
3. keep a single owner for status constraints or PostgreSQL enum changes;
4. test forward migration on both an empty schema and a Phase 02 snapshot;
5. test application startup and repository mappings after migration;
6. use a forward corrective migration in shared environments; do not edit an already-applied Flyway file.

## 7. Persistence Invariants

- no `TRIAGED` ticket lacks category, priority, queue, triager, or triage time;
- `OPEN` tickets may keep triage fields null;
- ticket version never decreases;
- history is append-only;
- outbox payload and ticket state are committed together;
- audit and timeline records cannot point to another tenant;
- user-provided reason is length-limited and safely encoded.

