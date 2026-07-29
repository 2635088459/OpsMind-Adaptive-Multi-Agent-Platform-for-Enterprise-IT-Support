# SPEC-TW-007 — Domain Rules

## 1. Aggregate Command

```text
TriageTicketCommand
  ticketId
  categoryId
  subcategoryId?
  priority
  supportQueueId
  reason
  expectedVersion
  idempotencyKey
  actorContext
  correlationId
```

The transport layer constructs this command. `actorContext`, tenant identity, and authorization claims come from trusted authentication middleware.

## 2. Aggregate Method

```text
Ticket.triage(classification, routing, actor, occurredAt)
```

The aggregate method must:

1. require current status `OPEN`;
2. require valid normalized identifiers and priority;
3. set `categoryId`, `subcategoryId`, `priority`, and `supportQueueId`;
4. set `triagedBy` and `triagedAt`;
5. change status to `TRIAGED`;
6. increment the aggregate version once;
7. produce a domain result used to create history, timeline, audit, and outbox records.

Catalog existence and queue authorization are application/domain-service checks performed before the aggregate mutation. They must still run inside the command's transaction or against a consistency model that cannot authorize inactive records.

## 3. State Rule

```text
OPEN --TriageTicket--> TRIAGED
```

No other source status is accepted. Retriage, category correction, priority changes, and queue transfers are separate future commands; they must not be smuggled through this endpoint.

## 4. Classification Rules

- category is required and active;
- subcategory is optional;
- a supplied subcategory must be active and have `parent_category_id = categoryId`;
- identifiers are stored, while display names are resolved by query projections;
- deactivating a catalog item later does not rewrite historic triage data.

## 5. Priority Rules

Accepted values:

```text
LOW
MEDIUM
HIGH
CRITICAL
```

The API does not infer priority. `CRITICAL` may require an additional permission such as `ticket:triage:critical`; if the project has not introduced this permission, queue triage permission is sufficient for this spec and the decision must remain explicit in configuration.

## 6. Queue Rules

- the queue exists in the same tenant and is active;
- the actor has `ticket:triage` and access to the target queue;
- Requesters cannot triage;
- Support Agents may triage only into authorized queues;
- Support Leads may triage across queues explicitly listed in their authorization scope;
- Automation Agents use a service identity and only explicit queue grants;
- the ticket has no assignee after triage; assignment belongs to `SPEC-TW-008`.

## 7. Actor and Time Rules

- `triagedBy` comes from the authenticated principal;
- `triagedAt` comes from the server clock in UTC;
- the application uses one captured `occurredAt` value for the ticket, history, timeline, audit, and event;
- the requester cannot impersonate a triager through body or headers.

## 8. Concurrency

`If-Match` is mandatory. The repository update must include both ticket ID/tenant and expected version:

```sql
UPDATE tickets
SET ..., version = version + 1
WHERE id = :ticketId
  AND tenant_id = :tenantId
  AND version = :expectedVersion
  AND status = 'OPEN';
```

Zero affected rows must be resolved without leaking tenant data:

1. missing/inaccessible ticket → `TICKET_NOT_FOUND`;
2. version mismatch → `VERSION_CONFLICT`;
3. matching version but non-`OPEN` status → `INVALID_TICKET_STATE`.

Only one of two concurrent commands against the same version may succeed.

## 9. Idempotency

The uniqueness scope is:

```text
tenantId + actorId + commandName + idempotencyKey
```

The canonical request hash includes `ticketId`, all normalized body fields, and the expected version. It excludes authorization tokens and correlation ID.

- new key → execute and store response;
- same key + same hash → return stored response;
- same key + different hash → `IDEMPOTENCY_KEY_REUSED`;
- failed validation/authorization does not reserve the key;
- once mutation begins, idempotency completion is part of the same transaction.

## 10. Transaction Boundary

One transaction performs:

1. acquire/validate idempotency record;
2. load ticket in tenant scope;
3. verify version and state;
4. validate category, subcategory, queue, and authorization;
5. mutate the aggregate;
6. update ticket;
7. insert status history;
8. insert timeline entry;
9. insert audit record;
10. insert outbox event;
11. store idempotent response;
12. commit.

The message broker is not called inside this transaction. A separate outbox publisher sends committed events.

## 11. Failure Invariant

Any exception before commit rolls back all writes. Retries must never create duplicate domain facts.

