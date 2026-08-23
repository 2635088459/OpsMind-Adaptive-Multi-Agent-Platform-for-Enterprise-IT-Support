# Domain Rules — SPEC-PG-007

## Required

- 06 may only output governance facts; decisions must bind policy version/input hash/reason codes/constraints; approval final decisions must be idempotent and unique; every governance state transition must write audit/outbox in the same transaction.
- Published policy versions are immutable.
- Approval final states are irreversible.
- Approval is valid only for matching approvalRequestId/sourceRequestId/requestHash.

## Forbidden

- direct Tool execution; direct Ticket/Workflow state mutation; Memory content writes; forged approval; bypassing separation of duties; cross-domain distributed transactions.
- Default allow on policy evaluator failure.
- Collapsing expired/cancelled/denied into generic denied.
- Silently modifying historical decisions or audit records.
