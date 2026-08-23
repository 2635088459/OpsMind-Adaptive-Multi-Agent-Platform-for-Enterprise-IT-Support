# 05 Tool Integration Gateway Implementation Roadmap

> Domain：Tool Integration Gateway
>
> Service：`tool-integration-gateway`
>
> 文档状态：Implementation Roadmap

## 1. 总目标

把 `05-tool-integration-gateway` 从 LLD 落成可实现的 phase/spec：让 Agent Runtime 只能通过 Gateway 提交工具意图，并由 Gateway 完成 capability 解析、policy/approval 对接、credential 隔离、connector 执行、result 标准化、outbox 发布、幂等恢复和审计闭环。

## 2. Phase 总览

| Phase | 名称 | Specs | 目标 |
|---|---|---|---|
| 00 | 工程基础 | `SPEC-TG-001` ～ `SPEC-TG-003` | 建立 tool-integration-gateway 的服务边界、schema baseline、outbox/processed-event/audit baseline。 |
| 01 | 请求接入与能力注册 | `SPEC-TG-004` ～ `SPEC-TG-006` | 实现 ToolRequest 聚合、Runtime API、Connector/Capability registry，为所有工具执行建立唯一入口。 |
| 02 | 策略与审批中介 | `SPEC-TG-007` ～ `SPEC-TG-009` | 接入 risk decision、approval required linkage、approval granted/denied 事件，确保高风险工具不可绕过审批。 |
| 03 | 执行 Worker 与 Connector | `SPEC-TG-010` ～ `SPEC-TG-015` | 实现执行调度、worker claim/lease、connector SDK、凭据准备、operation key、副作用防护、结果标准化与 tool.completed 发布。 |
| 04 | 重试、对账与取消 | `SPEC-TG-016` ～ `SPEC-TG-019` | 实现 retry policy、timeout/partial side effect reconciliation、cancel 流程和 connector health 状态。 |
| 05 | 安全与凭据边界 | `SPEC-TG-020` ～ `SPEC-TG-021` | 落实 secret isolation、raw output 受控访问、授权 scope 与 network policy。 |
| 06 | 跨域契约闭环 | `SPEC-TG-022` ～ `SPEC-TG-025` | 闭环 03 Runtime、06 Policy/Approval、04 Memory Knowledge、02 Ticket/Workflow 相关契约。 |
| 07 | 可观测性、审计与管理 | `SPEC-TG-026` ～ `SPEC-TG-029` | 补齐 metrics/logs/traces、audit query、outbox poison/admin repair、connector admin lifecycle。 |
| 08 | 恢复、扩展与降级 | `SPEC-TG-030` | 实现 crash recovery、backpressure、worker scaling 和 degraded execution control。 |
| 09 | 最终验证与发布 | `SPEC-TG-031` ～ `SPEC-TG-032` | 完成 e2e/contract harness、最终覆盖审计和 release readiness。 |

## 3. 闭环原则

- Agent 不能直接调用 Tool，必须通过 Runtime 创建 Tool Request，再由 Gateway 执行。
- 05 不拥有 Ticket state，也不拥有 Workflow state；只拥有 Tool Request/Execution state。
- 任何外部副作用必须有 operation key、audit record 和恢复策略。
- 高风险工具必须等待 06 Policy/Approval 的明确批准。
- 凭据不能进入 Agent context、Runtime checkpoint、Ticket comment、Memory、event payload 或 logs。
- `tool.completed.v1` 只代表工具执行结束，不代表 ticket resolved 或 workflow completed。
- 所有消费事件必须 processed-event 去重；所有发布事件必须通过 Gateway outbox。
