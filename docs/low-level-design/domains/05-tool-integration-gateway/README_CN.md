# Tool Integration Gateway LLD

## 范围

本目录定义 `05-tool-integration-gateway` 的低层设计。该域负责把 Agent Runtime 产生的工具意图转化为受控、可审批、可审计、可幂等、可恢复的工具执行。

Tool Integration Gateway 是所有外部工具、内部运维工具、SaaS API、脚本、自动化连接器的唯一执行边界。Agent 不能直接调用 Tool；Agent 只能通过 `03-agent-runtime-orchestration` 创建 Tool Request，然后由本域完成风险评估、策略检查、审批对接、凭据注入、执行调度、结果标准化和事件发布。

本域不拥有 Ticket 生命周期状态，也不拥有 Agent Workflow state。Ticket state 仍由 `02-ticket-workflow` 管理，Agent Workflow state 仍由 `03-agent-runtime-orchestration` 管理。本域只维护 Tool Request、Tool Execution、Connector、Credential Binding、Approval Linkage 和 Tool Result state。

## 核心回答

- Agent 能不能直接调 Tool？不能。Agent 必须通过 Runtime 创建 Tool Request，Tool Gateway 是唯一执行入口。
- Tool Request 是 Runtime 提交的工具调用意图，表达要做什么、为什么做、关联哪个 ticket/workflow/task、输入是什么、需要什么能力。
- Tool Execution 是 Gateway 对 Tool Request 的一次可调度执行尝试，包含审批状态、凭据绑定、connector 选择、重试、timeout、结果和审计信息。
- Tool Connector 是对具体工具/API/脚本/SaaS 的受控适配器，必须声明 capability、risk level、input schema、output schema、timeout、retry policy 和 secret requirements。
- Tool Gateway 通过 Policy/Approval Governance 判断风险与审批要求，但不自己做最终治理规则的所有权。
- 凭据不能暴露给 Agent、Runtime 或 Ticket Workflow。凭据只在 Gateway 执行边界内按最小权限短时注入。
- `tool.completed` 只表示工具执行完成，不表示 ticket 已解决，也不表示 workflow 已完成。
- Tool Execution state 与 Ticket state、Workflow state 分离，只通过 ids 和事件关联。
- 幂等由 request idempotency key、execution attempt、connector operation key、processed event table 和 outbox 共同保证。
- Runtime 崩溃不会丢结果；Gateway 发布 `tool.completed.v1` 后由 Runtime 幂等消费。
- Gateway 崩溃后通过 Tool Request/Execution 表、lease、checkpoint、outbox replay 和 connector reconciliation 恢复。

## 为什么需要独立 Tool Gateway

如果 Agent 直接调用工具，会产生四个系统性风险：

- Agent 可能绕过审批、风险策略和最小权限控制。
- 外部副作用难以幂等，重复执行可能造成生产事故。
- 原始凭据和敏感工具输出会泄漏到 Agent 上下文或长期记忆。
- Ticket/Workflow 无法获得统一审计链，事后无法证明谁请求、谁批准、执行了什么、结果是什么。

因此 Tool Gateway 是一个隔离层：Agent 负责推理和提出意图，Runtime 负责编排，Gateway 负责执行边界，Policy 负责规则，Ticket Workflow 负责业务状态。

## 与其他域的关系

- `02-ticket-workflow`：接收工具结果间接影响 ticket 流程，但不直接调用 connector。
- `03-agent-runtime-orchestration`：创建 Tool Request，等待 `tool.completed.v1` 恢复 workflow。
- `04-memory-knowledge`：可以接收标准化 tool evidence，但不能执行工具，也不能保存未脱敏 raw output。
- `06-policy-approval-governance`：提供 risk decision、approval requirement、approval result 和 policy audit。
- `07-evaluation-improvement`：评估 tool success rate、误用率、自动化收益和 connector quality。
- `08-observability-platform`：汇聚 logs、metrics、traces、audit events。

## 14 个 LLD 切面

1. `01-domain-model`：Tool Request、Tool Execution、Connector、Capability、Credential Binding、Tool Result。
2. `02-business-invariants`：唯一入口、凭据隔离、状态分离、审批边界、审计不可绕过。
3. `03-state-machine`：Tool Request、Execution Attempt、Approval Linkage、Connector Health 状态机。
4. `04-use-cases`：提交请求、低风险自动执行、高风险审批、重试、取消、结果回流。
5. `05-api-contracts`：Runtime API、admin connector API、internal execution API、health API。
6. `06-event-contracts`：消费 tool request / approval / policy 事件，发布 tool lifecycle 事件。
7. `07-data-model`：PostgreSQL 表、唯一键、审计表、outbox、connector registry。
8. `08-transaction-and-outbox`：请求入库、审批决策、执行结果、outbox 发布顺序。
9. `09-concurrency-and-idempotency`：claim lease、重复请求、connector side effect key、重复事件。
10. `10-failure-handling`：connector timeout、partial side effect、poison request、reconciliation。
11. `11-security`：secret handling、RBAC/ABAC、redaction、network allowlist、audit。
12. `12-observability`：执行 latency、success rate、approval wait、risk distribution、trace。
13. `13-package-and-class-design`：ports/adapters、service、repository、worker、connector SDK。
14. `14-testing-strategy`：unit、integration、contract、idempotency、security、recovery tests。

## 冻结原则

05 的冻结设计必须满足：

- 所有工具执行都可追溯到 `ticketId`、`workflowInstanceId`、`agentTaskId` 和 `requestedBy`。
- 所有外部副作用都有幂等键和审计记录。
- 所有工具结果都有标准化 envelope、redaction 状态和 evidence reference。
- 所有需要审批的工具执行都必须等待 `06-policy-approval-governance` 的明确批准。
- 任何 connector 失败都不能直接推进 Ticket state，只能发布失败事实并交回 Runtime/Ticket Workflow 决策。

