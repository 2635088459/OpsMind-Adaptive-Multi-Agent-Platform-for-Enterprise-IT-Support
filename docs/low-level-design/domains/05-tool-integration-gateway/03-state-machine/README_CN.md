# 03 State Machine

## Tool Request 状态机

```text
RECEIVED
  -> VALIDATING
  -> POLICY_CHECKING
  -> WAITING_APPROVAL
  -> APPROVED
  -> QUEUED
  -> EXECUTING
  -> COMPLETED

RECEIVED -> REJECTED
VALIDATING -> REJECTED
POLICY_CHECKING -> POLICY_DENIED
WAITING_APPROVAL -> APPROVAL_DENIED
QUEUED -> CANCELLED
EXECUTING -> CANCEL_REQUESTED
EXECUTING -> COMPLETED
EXECUTING -> FAILED
FAILED -> QUEUED
FAILED -> TERMINAL_FAILED
```

## Tool Request 状态语义

- `RECEIVED`：Gateway 已收到请求，但尚未持久化完整校验结果。
- `VALIDATING`：校验 idempotency、schema、capability、actor 和 ticket/workflow refs。
- `POLICY_CHECKING`：等待或执行 policy/risk decision。
- `WAITING_APPROVAL`：需要人工或治理审批。
- `APPROVED`：审批通过或低风险自动批准。
- `QUEUED`：已可执行，等待 worker claim。
- `EXECUTING`：存在 active ToolExecution attempt。
- `COMPLETED`：至少一个 execution attempt 成功或以明确 final status 结束，并已发布结果。
- `FAILED`：可重试失败。
- `TERMINAL_FAILED`：不可重试失败。
- `POLICY_DENIED`：策略拒绝。
- `APPROVAL_DENIED`：审批拒绝。
- `CANCEL_REQUESTED`：取消请求已记录，等待 active connector 可中断或完成。
- `CANCELLED`：未执行或已安全取消。
- `REJECTED`：请求本身非法，不进入执行。

## Execution Attempt 状态机

```text
CREATED
  -> CLAIMED
  -> PREPARING
  -> INVOKING
  -> NORMALIZING_RESULT
  -> COMPLETED

CLAIMED -> LEASE_EXPIRED
PREPARING -> FAILED
INVOKING -> TIMED_OUT
INVOKING -> FAILED
INVOKING -> PARTIAL_SIDE_EFFECT
NORMALIZING_RESULT -> FAILED
FAILED -> RETRY_SCHEDULED
TIMED_OUT -> RECONCILING
PARTIAL_SIDE_EFFECT -> RECONCILING
RECONCILING -> COMPLETED
RECONCILING -> TERMINAL_FAILED
```

## Approval Linkage 状态机

```text
NOT_REQUIRED
REQUIRED -> APPROVAL_REQUESTED -> APPROVED
REQUIRED -> APPROVAL_REQUESTED -> DENIED
APPROVAL_REQUESTED -> EXPIRED
APPROVAL_REQUESTED -> CANCELLED
```

Gateway 只保存审批 linkage 和 decision snapshot。审批规则、审批人、审批 SLA 和审批历史归 `06-policy-approval-governance` 所有。

## Connector Health 状态机

```text
ACTIVE -> DEGRADED -> ACTIVE
ACTIVE -> DISABLED
DEGRADED -> DISABLED
DISABLED -> ACTIVE
ACTIVE -> DEPRECATED
DEPRECATED -> DISABLED
```

执行调度只能选择 `ACTIVE` connector。`DEGRADED` connector 只允许只读或低风险 fallback，除非 policy 明确允许。

## 状态分离

`ToolRequest.COMPLETED` 不会推进：

- `Ticket.RESOLVED`
- `Ticket.CLOSED`
- `Workflow.COMPLETED`
- `AgentTask.COMPLETED`

Gateway 发布 `tool.completed.v1` 后，由 Runtime 消费并决定 agent task 是否完成；Ticket Workflow 再根据 workflow/tool/verification 事实决定 ticket 是否迁移。

