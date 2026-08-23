# 08 Transaction And Outbox

## 事务原则

06 的状态迁移必须遵循：

1. 保存 policy/decision/approval 事实；
2. 同事务写 governance audit；
3. 同事务写 outbox event；
4. 事务提交后异步发布。

## Policy Decision

同一事务内：

1. 根据有效 policy version 计算 decision。
2. 插入 `policy_decisions`。
3. 插入 `governance_audit_records`。
4. 插入 `policy.decision.created.v1` outbox。

重复 `decisionKey + inputHash` 返回已有 decision，不生成新事件。

## Approval Request

同一事务内：

1. 插入或复用 `approval_requests`。
2. 写 audit。
3. 写 `approval.requested.v1` outbox。

重复 request 返回同一个 approvalRequestId。

## Approval Decision

同一事务内：

1. `SELECT ... FOR UPDATE` 锁定 ApprovalRequest。
2. 校验 status 为 `REQUESTED`。
3. 校验 approver 权限与职责分离。
4. 插入 `approval_decisions`。
5. 更新 ApprovalRequest final status。
6. 写 audit。
7. 写 `approval.granted.v1` 或 `approval.denied.v1` outbox。

## Expiry Worker

Expiry worker 扫描 `REQUESTED` 且 `expires_at < now()` 的 approval request，进入 `EXPIRED` 并发布 `approval.expired.v1`。

## Outbox

Publisher 必须使用稳定 eventId、publish confirm、重试和 dead-letter 状态。

