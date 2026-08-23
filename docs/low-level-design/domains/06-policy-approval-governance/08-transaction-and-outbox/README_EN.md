# 08 Transaction And Outbox

## Transaction Principles

06 state transitions follow:

1. persist policy/decision/approval facts;
2. write governance audit in the same transaction;
3. write outbox event in the same transaction;
4. publish asynchronously after commit.

## Policy Decision

In one transaction:

1. Evaluate decision using effective policy version.
2. Insert `policy_decisions`.
3. Insert `governance_audit_records`.
4. Insert `policy.decision.created.v1` outbox.

Duplicate `decisionKey + inputHash` returns existing decision and does not create a new event.

## Approval Request

In one transaction:

1. Insert or reuse `approval_requests`.
2. Write audit.
3. Write `approval.requested.v1` outbox.

Duplicate request returns the same approvalRequestId.

## Approval Decision

In one transaction:

1. Lock ApprovalRequest using `SELECT ... FOR UPDATE`.
2. Verify status is `REQUESTED`.
3. Verify approver permission and separation of duties.
4. Insert `approval_decisions`.
5. Update ApprovalRequest final status.
6. Write audit.
7. Write `approval.granted.v1` or `approval.denied.v1` outbox.

## Expiry Worker

Expiry worker scans approval requests in `REQUESTED` with `expires_at < now()`, moves them to `EXPIRED`, and publishes `approval.expired.v1`.

## Outbox

Publisher must use stable eventId, publish confirm, retry, and dead-letter state.

