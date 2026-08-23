# Domain Rules — SPEC-TG-032

## Required

- Tool execution must go through Gateway; state must remain separate from Ticket/Workflow; external side effects must be idempotent, auditable, and recoverable; published events must use outbox; consumed events must use processed-event deduplication.
- `tool.completed.v1` does not mean Ticket resolved or Workflow completed.
- Connector capability is not permission; actor, scope, policy, and credential binding must still be checked before execution.
- Mutation connectors must have an operation key.

## Forbidden

- direct Ticket/Workflow state writes; direct Agent tool calls; secret/raw-output leakage; bypassing Policy/Approval; cross-domain distributed transactions.
- Writing connector raw output directly into Memory Knowledge.
- Executing external connector calls inside a database transaction.
- Collapsing policy denied, approval denied, timeout, and partial side effect into generic failed.
