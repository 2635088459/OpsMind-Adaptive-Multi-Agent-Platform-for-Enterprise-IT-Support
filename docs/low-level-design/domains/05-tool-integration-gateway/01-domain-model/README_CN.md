# 01 Domain Model

## 聚合边界

Tool Integration Gateway 的核心聚合是 `ToolRequest`。它代表一次由 Agent Runtime 提交、需要 Gateway 管理的工具调用意图。

`ToolRequest` 不等于实际外部调用。实际调用由一个或多个 `ToolExecution` attempt 表达。这样可以把用户/Agent 的意图、审批等待、connector 执行、重试、结果归档拆开管理。

## 核心实体

### ToolRequest

字段语义：

- `toolRequestId`：本域聚合 id。
- `idempotencyKey`：Runtime 提交请求时提供的业务幂等键。
- `ticketId` / `ticketCycleId`：关联 ticket，不拥有 ticket state。
- `workflowInstanceId` / `agentTaskId`：关联 Runtime，不拥有 workflow state。
- `requestedByType`：`AGENT`、`SYSTEM`、`HUMAN_OPERATOR`。
- `requestedById`：请求来源主体。
- `capabilityName`：需要的能力，例如 `kubernetes.restartDeployment`。
- `toolName`：可选的目标工具；如果为空，由 Gateway 根据 capability 选择 connector。
- `inputPayload`：标准化输入。
- `reason`：为什么需要执行工具，必须可审计。
- `riskSnapshot`：Gateway/Policy 决策时保存的风险快照。
- `status`：请求生命周期状态。

### ToolExecution

`ToolExecution` 是 ToolRequest 的一次执行尝试。

关键字段：

- `executionId`
- `toolRequestId`
- `attemptNumber`
- `connectorId`
- `connectorVersion`
- `operationKey`
- `leaseOwner`
- `leaseExpiresAt`
- `status`
- `startedAt`
- `completedAt`
- `timeoutAt`
- `resultEnvelopeId`

`operationKey` 是传递给 connector 或外部系统的副作用幂等键。任何可能改变外部状态的 connector 都必须支持或模拟此键。

### ToolConnector

`ToolConnector` 是具体工具适配器注册项。

必须声明：

- `connectorId`
- `name`
- `version`
- `capabilities`
- `inputSchema`
- `outputSchema`
- `riskLevel`
- `requiresApproval`
- `secretRequirements`
- `networkPolicy`
- `timeoutPolicy`
- `retryPolicy`
- `healthStatus`

### Capability

Capability 是 Gateway 暴露给 Runtime 的稳定能力名，不等于某个具体工具。Runtime 应按 capability 提交请求，Gateway 决定用哪个 connector 实现。

示例：

- `ticket.enrichFromCmdb`
- `kubernetes.getPodLogs`
- `kubernetes.restartDeployment`
- `slack.notifyChannel`
- `servicenow.createChangeRequest`

### CredentialBinding

`CredentialBinding` 描述 connector 执行时如何获取凭据。

凭据值不保存在业务表中，只保存 vault reference、scope、rotation metadata、lastUsedAt 和 audit reference。

### ToolResultEnvelope

所有 connector output 必须标准化为 Tool Result Envelope：

- `resultEnvelopeId`
- `executionId`
- `status`
- `summary`
- `structuredOutput`
- `rawOutputRef`
- `redactionStatus`
- `evidenceRefs`
- `externalResourceRefs`
- `errorCode`
- `retryable`

原始输出默认不进入事件 payload；事件只携带摘要、引用和脱敏后的结构化结果。

## 值对象

- `RiskDecisionRef`：指向 Policy/Approval 域的风险决策。
- `ApprovalRequestRef`：指向审批请求。
- `ConnectorInvocationSpec`：执行 connector 的标准入参。
- `RedactionMetadata`：输出脱敏与分类结果。
- `AuditActor`：请求者、批准者、执行 worker、connector identity。

## 聚合规则

- `ToolRequest` 可以没有 `ToolExecution`，例如等待审批或被 policy 拒绝。
- 一个 `ToolRequest` 可以有多个 `ToolExecution` attempts，但同一时间只能有一个 active attempt。
- 一个 `ToolExecution` 只能属于一个 `ToolRequest`。
- `ToolConnector` 是 registry/config 实体，不属于 ToolRequest 聚合。
- `CredentialBinding` 被 execution 引用，但凭据值不能进入 request/execution/result 表。

