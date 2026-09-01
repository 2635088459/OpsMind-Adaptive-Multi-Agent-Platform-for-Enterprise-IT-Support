# SPEC-ARO-038 — Start Conversation Creates Ticket

> Domain: Agent Runtime Orchestration
>
> Phase: 10 — Conversational Intake
>
> Service: `agent-runtime-service`
>
> LLD Mapping: `04-use-cases`, `05-api-contracts`, `08-transaction-and-outbox`
>
> Document Status: Spec Planning

## 1. Goal

Implement `POST /api/v1/conversations`: for real, create a ticket in `02-ticket-workflow`, then create a `conversational_intake` `WorkflowInstance` (SPEC-ARO-037) bound to that real ticket — the entry point every conversation goes through.

## 2. Scope

Includes:

- The new public REST endpoint and its request/response shape;
- The real outbound HTTP call to `02-ticket-workflow`'s own `POST /api/v1/tickets`;
- Creating the `WorkflowInstance` directly via the internal command (not the `ticket-created` event-ingestion endpoint), since the `ticketId` is already known synchronously within this same request.

Excludes:

- Message-turn execution (SPEC-ARO-039) or confirm/decline (SPEC-ARO-040);
- The service-identity mechanism itself used to authenticate the outbound call (SPEC-ARO-043, a dependency of this spec).

## 3. Core Rules

- The ticket created is always real, via `02-ticket-workflow`'s own endpoint — never fabricated or simulated locally.
- `WorkflowInstance` creation never happens without a prior, successful, real ticket creation.
- The request requires an `Idempotency-Key`, following the platform's existing convention.
- If ticket creation succeeds but workflow-instance creation subsequently fails, the ticket is left in its real, normal state (`NEW`) and remains visible through `02-ticket-workflow`'s own normal queries — it is never silently hidden or orphaned.
