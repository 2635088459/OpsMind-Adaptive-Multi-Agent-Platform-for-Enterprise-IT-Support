# SPEC-MK-009 Acceptance Criteria

## Criteria

- Deliver the minimum closed-loop capability for `Embedding And Index Activation`.
- Cover LLD mapping: `07-data-model, 08-transaction-and-outbox, 10-failure-handling`.
- Every new write path has an idempotency key, unique key, or optimistic version.
- Every evidence result returned to Runtime/Agents has provenance.
- The spec does not directly mutate Ticket state or Workflow state.
- Unit, application, and contract test plans are implementable.

## Definition Of Done

- Code, migration, API/event contracts, tests, and traceability-entry are complete.
- 02/03-related contracts include happy path, duplicate delivery, and invalid payload tests.
