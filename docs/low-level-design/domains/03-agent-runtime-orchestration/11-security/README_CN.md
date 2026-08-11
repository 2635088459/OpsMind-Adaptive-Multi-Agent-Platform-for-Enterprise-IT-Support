# 11 Security

## 安全边界

Agent Runtime 是高权限编排层，但 Agent 本身不应拥有直接企业系统权限。

安全原则：

- Agent 不直接调用 Tool。
- Agent 不持有第三方系统凭据。
- Tool Gateway 执行统一授权、审计、速率限制和 policy evaluation。
- Runtime 只保存 policy snapshot 和 request metadata，不保存明文 secret。

## Agent Identity

每个 Agent Task 执行必须带：

- `agentRole`
- `workerId`
- `claimToken`
- `workflowInstanceId`
- `ticketId`
- `correlationId`

审计中必须能回答：哪个 Agent 角色在什么 Workflow 中基于什么输入产出了什么决策。

## Tool Gateway 强制路径

禁止：

- Agent Worker 直接依赖 Jira、Slack、Kubernetes、Cloud Provider、Database 等 client。
- Task handler 绕过 Runtime 创建外部副作用。
- 在 checkpoint 中保存 Tool credential。

允许：

- Agent 产出 `ToolRequestDraft`。
- Runtime 校验并持久化 Tool Request。
- Tool Gateway 根据 policy 执行工具。
- Runtime 消费 Tool Gateway 的完成事件。

## Authorization

Runtime 命令授权：

- start workflow：仅 event consumer 或受信内部服务。
- pause/resume：运维、Ticket Workflow 或 policy engine。
- claim/complete task：受信 worker identity。
- replay/recover：admin only。

## Data Protection

- payload 中 PII 必须最小化。
- checkpoint 只保存恢复所需信息。
- Agent reasoning 原始长文本需要摘要化或脱敏后保存。
- logs 中禁止输出 secret、token、完整工具响应。

## Audit

必须审计：

- workflow state transition
- task claim/complete/fail
- tool request created
- tool result consumed
- pause/resume command
- recovery action
- admin override
