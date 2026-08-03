# SPEC-TW-008 — Domain Rules

## 1. Commands

```text
AssignTicket(ticketId, assigneeId, reason, expectedVersion, idempotencyKey, actorContext)
ReassignTicket(ticketId, assigneeId, reason, expectedVersion, idempotencyKey, actorContext)
UnassignTicket(ticketId, reason, expectedVersion, idempotencyKey, actorContext)
```

Tenant, actor, roles, and queue claims come from trusted authentication middleware.

## 2. Aggregate Methods

```text
Ticket.assign(assignee, actor, reason, occurredAt)
Ticket.reassign(newAssignee, actor, reason, occurredAt)
Ticket.unassign(actor, reason, occurredAt)
```

External identity and queue membership are validated by the application layer before the aggregate method is called. The aggregate still enforces ticket-local invariants.

## 3. Initial Assignment

The aggregate must:

1. require status `TRIAGED`;
2. require `assigneeId == null`;
3. set `assigneeId`, `assignedAt`, and `assignedBy`;
4. change status to `ASSIGNED`;
5. increment version exactly once;
6. produce ownership and status-change facts.

## 4. Reassignment

The aggregate must:

1. require a current assignee;
2. allow status `ASSIGNED`, `IN_PROGRESS`, `WAITING_FOR_USER`, or `WAITING_FOR_APPROVAL`;
3. require a different new assignee;
4. replace assignee and assignment metadata;
5. preserve the status;
6. increment version exactly once.

## 5. Unassignment

The aggregate must:

1. require status `ASSIGNED`;
2. require a current assignee;
3. clear assignee and assignment metadata;
4. change status to `TRIAGED`;
5. increment version exactly once.

## 6. Assignee Eligibility

At command time, the assignee must:

- exist in the same tenant;
- be active and not suspended/deleted;
- have a support-capable role;
- be an active member of the ticket's `supportQueueId`.

Eligibility is evaluated within the command's consistency boundary. A later membership change does not rewrite historical facts.

## 7. Actor Authorization

- Requesters cannot execute ownership commands.
- Support Agents may act only where policy grants the operation and queue.
- Support Leads may assign, reassign, and unassign within managed queues.
- Automation Agents require explicit service policy and queue scope.
- Cross-tenant access always behaves as not found or denied according to the shared security policy.

## 8. Invariants

- `TRIAGED` means no assignee.
- `ASSIGNED`, `IN_PROGRESS`, and waiting states require an assignee.
- A command cannot silently become another operation.
- Queue changes and ownership changes are separate commands.
- Stored history is append-only.
- Version increases once per committed aggregate command.

## 9. Concurrency and Idempotency

The repository update must include `WHERE id = ? AND tenant_id = ? AND version = ?`. Zero updated rows causes `VERSION_CONFLICT`.

Idempotency is checked before side effects. Identical replay returns the stored status, body, headers, and resource version. Key reuse with a different fingerprint is rejected.

## 10. Domain Facts

```text
TicketAssigned
TicketReassigned
TicketUnassigned
```

Facts contain identifiers and business metadata only. They must not contain bearer tokens, raw authorization claims, private messages, or full user profiles.

## 11. Application Handler Order

1. authenticate and validate transport data;
2. claim/check idempotency;
3. load tenant-scoped ticket;
4. authorize actor against operation and ticket queue;
5. validate expected version;
6. resolve and validate assignee when applicable;
7. invoke aggregate method;
8. persist all records in one transaction;
9. store replayable response;
10. return response and ETag.
