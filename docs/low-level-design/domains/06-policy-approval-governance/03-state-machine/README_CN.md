# 03 State Machine

## Policy 状态机

```text
DRAFT -> REVIEWING -> PUBLISHED -> DEPRECATED -> ARCHIVED
DRAFT -> CANCELLED
REVIEWING -> REJECTED -> DRAFT
PUBLISHED -> SUPERSEDED
```

`PUBLISHED` policy version 不可修改。新规则必须生成新 version。

## Policy Decision 状态机

```text
EVALUATING -> ALLOWED
EVALUATING -> DENIED
EVALUATING -> APPROVAL_REQUIRED
EVALUATING -> ALLOWED_WITH_CONSTRAINTS
EVALUATING -> EVALUATION_FAILED

APPROVAL_REQUIRED -> APPROVAL_LINKED
```

Decision 是快照，final 后不再迁移；approval lifecycle 由 ApprovalRequest 表达。

## Approval Request 状态机

```text
REQUESTED -> APPROVED
REQUESTED -> DENIED
REQUESTED -> EXPIRED
REQUESTED -> CANCELLED
REQUESTED -> SUPERSEDED
```

只有 `REQUESTED` 可以被审批。final 状态不可逆。

## Override 状态机

```text
OVERRIDE_REQUESTED -> OVERRIDE_APPROVED
OVERRIDE_REQUESTED -> OVERRIDE_DENIED
OVERRIDE_REQUESTED -> OVERRIDE_EXPIRED
OVERRIDE_APPROVED -> OVERRIDE_USED
OVERRIDE_APPROVED -> OVERRIDE_REVOKED
```

Override 必须绑定 reason、scope、expiresAt 和 approver。

## 状态分离

Approval approved 不会自动执行工具，也不会完成 ticket/workflow。它只发布 `approval.granted.v1`，由 05/03/02 幂等消费。

