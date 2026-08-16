# SPEC-MK-007 Acceptance Criteria

## Criteria

- Deliver the minimum closed-loop capability for `Knowledge Document Aggregate And Ingestion API`.
- Cover LLD mapping: `01-domain-model, 05-api-contracts`.
- Every new write path has an idempotency key, unique key, or optimistic version.
- Every evidence result returned to Runtime/Agents has provenance.
- The spec does not directly mutate Ticket state or Workflow state.
- Unit, application, and contract test plans are implementable.

## Definition Of Done

- Code, migration, API/event contracts, tests, and traceability-entry are complete.
- 02/03-related contracts include happy path, duplicate delivery, and invalid payload tests.
