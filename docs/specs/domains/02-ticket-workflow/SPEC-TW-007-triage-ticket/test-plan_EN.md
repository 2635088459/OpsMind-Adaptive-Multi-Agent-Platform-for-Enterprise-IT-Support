# SPEC-TW-007 — Test Plan and Implementation Checklist

## 1. TDD Sequence

### Step 1 — Domain Unit Tests

Write failing tests for:

- `OPEN → TRIAGED`;
- rejection from every non-`OPEN` status;
- required category, priority, queue, actor, and time;
- optional subcategory;
- version increments once;
- generated domain result contains approved before/after values.

### Step 2 — Validation and Authorization Tests

- category missing/inactive;
- subcategory missing/inactive/wrong parent;
- queue missing/inactive/cross-tenant;
- Requester forbidden;
- Support Agent allowed/denied by queue;
- Support Lead scope;
- Automation Agent explicit grant;
- malformed UUID, enum, reason length, and unknown body field.

### Step 3 — Repository and Migration Tests

- migration from Phase 02 snapshot;
- ticket field mappings;
- tenant-scoped optimistic update;
- status history append;
- timeline/audit/outbox/idempotency inserts;
- persistence constraints reject inconsistent direct writes.

### Step 4 — Handler Integration Tests

- complete success transaction;
- no-subcategory success;
- every stable error mapping;
- transaction rollback after each simulated side-write failure;
- captured timestamp reused across records;
- actor comes from authentication context.

### Step 5 — API Contract Tests

- headers and path validation;
- `200` response schema and `ETag`;
- RFC 9457-style error envelope;
- `428` missing `If-Match`;
- `412` stale ETag;
- OpenAPI request/response validation.

### Step 6 — Idempotency and Concurrency Tests

- same key/same hash replay;
- same key/different hash conflict;
- two concurrent commands using version 7: exactly one `200`, one `412`;
- replay after success does not duplicate status history, timeline, audit, or outbox;
- retry after pre-mutation validation failure may use the same key.

### Step 7 — Event Contract Tests

- exact `ticket.triaged.v1` name and v1 schema;
- UTC timestamp;
- partition/aggregate ID is ticket ID;
- nullable subcategory;
- no secret/request-message fields;
- AsyncAPI schema validates serialized event.

## 2. Minimum Test Matrix

| ID | Layer | Scenario | Expected |
|---|---|---|---|
| UT-01 | Domain | valid OPEN ticket | TRIAGED, version + 1 |
| UT-02 | Domain | ticket already TRIAGED | `INVALID_TICKET_STATE` |
| UT-03 | Domain | optional subcategory absent | success with null |
| AU-01 | Auth | Requester | 403 |
| AU-02 | Auth | agent lacks queue access | 403 |
| VA-01 | Validation | inactive category | 422 |
| VA-02 | Validation | wrong-parent subcategory | 422 |
| VA-03 | Validation | inactive queue | 422 |
| AP-01 | API | missing If-Match | 428 |
| AP-02 | API | stale If-Match | 412 |
| ID-01 | Idempotency | same key/same request | stored 200, no duplicate |
| ID-02 | Idempotency | same key/different request | 409 |
| DB-01 | Integration | outbox insert fails | total rollback |
| DB-02 | Integration | two writers | one success only |
| EV-01 | Contract | serialized event | matches AsyncAPI |
| SEC-01 | Security | cross-tenant ticket | indistinguishable 404 |

## 3. Test Data Builders

Provide builders/fixtures for:

```text
openTicket()
activeCategory()
activeSubcategory(categoryId)
activeSupportQueue()
authorizedSupportAgent(queueId)
requester()
triageRequest()
```

Use a fixed clock and deterministic UUID provider in unit tests. Integration tests may use real UUIDs but must assert relationships, not incidental order.

## 4. Observability Verification

Verify:

- success and bounded failure counters;
- duration histogram/timer;
- version-conflict counter;
- structured log keys;
- correlation context flows HTTP → transaction → outbox publish;
- no high-cardinality labels such as ticket ID on metrics.

## 5. Security Checks

- server ignores/rejects actor impersonation fields;
- tenant scope is present in all reads and writes;
- error messages do not reveal inaccessible resources;
- logs redact Authorization and secrets;
- reason is length-limited and safely rendered;
- service identity cannot exceed explicit queue grants.

## 6. Implementation Checklist

- [ ] Acceptance tests committed first
- [ ] Command and handler implemented
- [ ] Aggregate rule centralized
- [ ] Catalog and queue authorization implemented
- [ ] Migration applied and repository mappings updated
- [ ] Ticket/history/timeline/audit/outbox/idempotency are atomic
- [ ] Optimistic locking and ETag implemented
- [ ] Idempotent replay implemented
- [ ] OpenAPI contract tests pass
- [ ] AsyncAPI event tests pass
- [ ] Rollback and concurrency tests pass
- [ ] Metrics, logs, and traces verified
- [ ] All stable errors documented
- [ ] `SPEC-TW-007` demo recorded or reproducible

## 7. Demo Script

1. Create an `OPEN` ticket through `SPEC-TW-001`.
2. Read it and capture `ETag`.
3. Triage it with valid classification and queue data.
4. Show `TRIAGED`, version increment, and new `ETag`.
5. Show timeline and status-history entries.
6. Show the pending/published outbox event.
7. Replay the same idempotency key and prove no duplicate records.
8. retry with the stale original ETag and show `412 VERSION_CONFLICT`.

