# 03 State Machine

## Policy State Machine

```text
DRAFT -> REVIEWING -> PUBLISHED -> DEPRECATED -> ARCHIVED
DRAFT -> CANCELLED
REVIEWING -> REJECTED -> DRAFT
PUBLISHED -> SUPERSEDED
```

A `PUBLISHED` policy version is immutable. New rules require a new version.

## Policy Decision State Machine

```text
EVALUATING -> ALLOWED
EVALUATING -> DENIED
EVALUATING -> APPROVAL_REQUIRED
EVALUATING -> ALLOWED_WITH_CONSTRAINTS
EVALUATING -> EVALUATION_FAILED

APPROVAL_REQUIRED -> APPROVAL_LINKED
```

Decision is a snapshot and does not transition after final; approval lifecycle is represented by ApprovalRequest.

## Approval Request State Machine

```text
REQUESTED -> APPROVED
REQUESTED -> DENIED
REQUESTED -> EXPIRED
REQUESTED -> CANCELLED
REQUESTED -> SUPERSEDED
```

Only `REQUESTED` can be approved or denied. Final states are irreversible.

## Override State Machine

```text
OVERRIDE_REQUESTED -> OVERRIDE_APPROVED
OVERRIDE_REQUESTED -> OVERRIDE_DENIED
OVERRIDE_REQUESTED -> OVERRIDE_EXPIRED
OVERRIDE_APPROVED -> OVERRIDE_USED
OVERRIDE_APPROVED -> OVERRIDE_REVOKED
```

Override must bind reason, scope, expiresAt, and approver.

## State Separation

Approval approved does not automatically execute tools or complete tickets/workflows. It publishes `approval.granted.v1`, consumed idempotently by 05/03/02.

