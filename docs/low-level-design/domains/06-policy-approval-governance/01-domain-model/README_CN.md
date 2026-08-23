# 01 Domain Model

## 聚合边界

06 的核心聚合是 `ApprovalRequest` 与 `PolicyDecision`。

`PolicyDecision` 表示一次治理判断的不可变快照。`ApprovalRequest` 表示一个等待人或治理主体决策的审批流程。二者可以关联，但不强制一一对应：一个 decision 可能无需审批，一个 approval request 可能包含多个 constraints。

## 核心实体

### Policy

Policy 是可版本化治理规则集合。

字段语义：

- `policyId`
- `policyName`
- `version`
- `status`
- `scope`
- `rules`
- `effectiveFrom`
- `effectiveTo`
- `createdBy`
- `publishedBy`

### PolicyRule

Rule 是 policy 内部可评估单元，表达 condition、effect、risk、approval requirement 和 constraints。

### PolicyDecision

字段语义：

- `policyDecisionId`
- `decisionKey`
- `inputHash`
- `subjectType`
- `subjectId`
- `actionType`
- `resourceType`
- `resourceId`
- `tenantId`
- `effect`
- `riskLevel`
- `approvalRequired`
- `constraints`
- `reasonCodes`
- `policyId`
- `policyVersion`
- `expiresAt`

`PolicyDecision` 一旦 final，不允许静默修改，只能生成新的 decision。

### ApprovalRequest

字段语义：

- `approvalRequestId`
- `requestKey`
- `sourceDomain`
- `sourceRequestId`
- `ticketId`
- `workflowInstanceId`
- `toolRequestId`
- `requestedBy`
- `approvalType`
- `riskLevel`
- `constraints`
- `status`
- `expiresAt`

### ApprovalDecision

字段语义：

- `approvalDecisionId`
- `approvalRequestId`
- `decision`
- `decidedBy`
- `decidedAt`
- `reason`
- `conditions`
- `separationOfDutiesCheck`

### GovernanceAudit

记录 policy evaluation、approval lifecycle、override、admin change 和 event publication 的审计事实。

## 值对象

- `RiskLevel`：`LOW`、`MEDIUM`、`HIGH`、`CRITICAL`。
- `DecisionEffect`：`ALLOW`、`DENY`、`REQUIRE_APPROVAL`、`ALLOW_WITH_CONSTRAINTS`。
- `ApprovalStatus`：`REQUESTED`、`APPROVED`、`DENIED`、`EXPIRED`、`CANCELLED`。
- `ReasonCode`：机器可读治理原因。
- `Constraint`：下游必须执行的限制，例如只读、时间窗、最大重试、需要验证。

