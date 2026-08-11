# 01 Domain Model

## 目标

定义 Agent Runtime Orchestration 的核心实体。该模型必须支持多 Agent 编排、暂停/恢复、崩溃恢复、Tool Gateway 调用、外部事件回调和审计。

## Workflow Instance 是什么

Workflow Instance 是 Runtime 为某个 Ticket 自动化周期创建的一次编排实例。

它包含：

- `workflowInstanceId`：Runtime 内部主键。
- `ticketId`：关联 Ticket，但不拥有 Ticket state。
- `ticketCycleId`：用于区分 reopen/reassign 后的新执行周期。
- `workflowType`：例如 `ticket_triage`, `approval_followup`, `remediation`, `verification_followup`。
- `state`：Runtime 自己的 Agent Workflow state。
- `definitionVersion`：使用哪一版编排定义。
- `workflowVersion`：乐观锁版本。
- `pauseGeneration`：暂停/恢复世代号。
- `currentCheckpointId`：最近一次稳定 checkpoint。

Workflow Instance 不是 Ticket。它不能把 Ticket 从 OPEN 改成 IN_PROGRESS，也不能直接关闭 Ticket。它只能发布 Runtime 事件，或通过明确的 Ticket Workflow command/request 请求业务域推进。

## Agent Task 是什么

Agent Task 是 Workflow Instance 内部最小可调度工作单元。

它包含：

- `agentTaskId`：任务主键。
- `workflowInstanceId`：所属 Workflow Instance。
- `agentRole`：执行角色，例如 `triage_agent`, `kb_agent`, `remediation_agent`, `verification_agent`。
- `taskType`：任务类型，例如 `classify`, `collect_context`, `propose_action`, `request_tool`, `evaluate_result`。
- `state`：Task 自己的状态。
- `dependsOn`：依赖的任务集合。
- `claimOwner` 与 `claimExpiresAt`：并发 worker claim 控制。
- `inputPayload` 与 `resultPayload`：结构化输入输出。
- `attempt` 与 `maxAttempts`：重试控制。

Agent Task 是 Runtime 内部概念，不应暴露成 Ticket 子状态。

## Checkpoint 怎么存

Checkpoint 是 Runtime 恢复执行的稳定快照。

最小字段：

- `checkpointId`
- `workflowInstanceId`
- `workflowVersion`
- `checkpointType`
- `cursor`
- `payloadJson`
- `payloadSchemaVersion`
- `checksum`
- `createdAt`

`payloadJson` 必须是结构化 JSON，保存可恢复上下文，例如 planner 输出、已完成 task、等待中的 tool request、等待中的 approval id、verification request id、agent scratchpad 摘要。

Checkpoint 不能保存明文 secret、一次性 token、Tool Gateway 凭据或不可审计的 Agent 私有状态。

## Tool Request

Agent 不能直接调用 Tool。Agent 只能向 Runtime 提交 Tool Request。Runtime 持久化请求并通过 Tool Gateway 发起调用。

Tool Request 包含：

- `toolRequestId`
- `workflowInstanceId`
- `agentTaskId`
- `toolName`
- `capability`
- `inputPayload`
- `policySnapshot`
- `gatewayCorrelationId`
- `state`

Tool 调用完成后，Runtime 消费 `tool.completed` 或 `tool.failed`，再恢复对应 Workflow。

## Event Cursor

Event Cursor 记录 Runtime 已经处理到哪个外部事件或内部步骤。它用于防止重复消费和崩溃后跳步。

Cursor 不代表 broker offset 的唯一真相。最终幂等依赖 `processed_events` 表和业务唯一键。
