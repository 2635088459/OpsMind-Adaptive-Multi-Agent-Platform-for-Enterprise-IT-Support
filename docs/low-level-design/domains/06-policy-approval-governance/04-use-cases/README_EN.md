# 04 Use Cases

## UC-PG-001: Tool Gateway Requests Risk Decision

1. 05 submits action, actor, resource, ticket/workflow refs, and input hash.
2. 06 selects effective policy version.
3. Rule evaluator computes effect, risk, approvalRequired, and constraints.
4. 06 persists PolicyDecision and audit.
5. 06 returns decision snapshot.

## UC-PG-002: Create Approval Request

1. 05/02/03 submits approval request.
2. 06 validates request hash, source linkage, and approver policy.
3. 06 persists ApprovalRequest.
4. 06 publishes `approval.requested.v1`.

## UC-PG-003: Approval Granted

1. Approver submits grant.
2. 06 validates permission, separation of duties, and request validity.
3. 06 persists ApprovalDecision.
4. ApprovalRequest enters `APPROVED`.
5. 06 publishes `approval.granted.v1`.

## UC-PG-004: Approval Denied/Expired/Cancelled

Denial, expiry, and cancellation must produce different final statuses and events so downstream domains can distinguish them.

## UC-PG-005: Policy Publication

1. Admin creates draft.
2. Reviewer reviews rules.
3. Publisher publishes new version.
4. 06 publishes `policy.published.v1`.
5. Downstream refreshes policy cache.

## UC-PG-006: High-Risk Override

Override is valid only within limited scope and time window, and requires independent approver plus higher audit level.

