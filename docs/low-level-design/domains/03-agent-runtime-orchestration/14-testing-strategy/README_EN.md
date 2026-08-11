# 14 Testing Strategy

## Unit Tests

Cover:

- Workflow state transition.
- Agent Task state transition.
- checkpoint payload schema/version.
- pause/resume idempotency rules.
- task dependency graph unlocking.
- Tool Gateway no-direct-call rule.

## Application Service Tests

Cover:

- consume `ticket.created` and create Workflow Instance.
- consume `approval.granted` and resume Workflow waiting for approval.
- consume `tool.completed` and resume Workflow waiting for tool result.
- consume `verification.completed` and complete or create remediation.
- pause writes checkpoint, increments generation, and publishes outbox.
- resume restores tasks and publishes outbox.

## Database Integration Tests

Cover:

- unique active workflow.
- processed event de-duplication.
- command idempotency request hash conflict.
- concurrent task claim.
- outbox publisher retry.
- latest checkpoint query.

## Contract Tests

Validate consumed events:

- `ticket.created.v1`
- `approval.granted.v1`
- `tool.completed.v1`
- `verification.completed.v1`

Validate published events:

- `workflow.started.v1`
- `workflow.paused.v1`
- `workflow.resumed.v1`
- `agent.task.completed.v1`
- `workflow.completed.v1`
- `workflow.failed.v1`

## Crash Recovery Tests

Must simulate:

- crash before transaction commit.
- crash before outbox publish.
- task worker crashes after claim.
- Runtime crashes after Tool Request creation.
- worker returns old-generation result during pause.
- duplicate external callback.

## Security Tests

Cover:

- Agent Worker has no Tool client.
- Tool Request must go through Tool Gateway.
- checkpoint does not contain secrets.
- admin API requires admin permission.
- log redaction.

## Acceptance Criteria

When a spec implementation is complete, at minimum:

- all core domain transition unit tests pass.
- event consumption idempotency tests pass.
- outbox write tests pass.
- duplicate pause/resume request tests pass.
- Tool Gateway boundary tests pass.
- at least one integration or component test covers crash recovery path.
