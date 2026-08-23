# 05 Tool Integration Gateway Phase / Spec Coverage Matrix

## 目标

本矩阵用于确认 `05-tool-integration-gateway` 的 phase/spec 拆分覆盖 LLD 14 个切面，并且能与 `02-ticket-workflow`、`03-agent-runtime-orchestration`、`04-memory-knowledge`、`06-policy-approval-governance` 闭环协作。

## Phase / Spec 总览

| Phase | Specs | 闭环目标 |
|---|---|---|
| 00 Engineering Foundation | `SPEC-TG-001` ～ `SPEC-TG-003` | 建立 tool-integration-gateway 的服务边界、schema baseline、outbox/processed-event/audit baseline。 |
| 01 Request Intake And Registry | `SPEC-TG-004` ～ `SPEC-TG-006` | 实现 ToolRequest 聚合、Runtime API、Connector/Capability registry，为所有工具执行建立唯一入口。 |
| 02 Policy Approval Mediation | `SPEC-TG-007` ～ `SPEC-TG-009` | 接入 risk decision、approval required linkage、approval granted/denied 事件，确保高风险工具不可绕过审批。 |
| 03 Execution Worker And Connectors | `SPEC-TG-010` ～ `SPEC-TG-015` | 实现执行调度、worker claim/lease、connector SDK、凭据准备、operation key、副作用防护、结果标准化与 tool.completed 发布。 |
| 04 Retry Reconciliation Cancellation | `SPEC-TG-016` ～ `SPEC-TG-019` | 实现 retry policy、timeout/partial side effect reconciliation、cancel 流程和 connector health 状态。 |
| 05 Security And Credential Boundary | `SPEC-TG-020` ～ `SPEC-TG-021` | 落实 secret isolation、raw output 受控访问、授权 scope 与 network policy。 |
| 06 Cross Domain Contracts | `SPEC-TG-022` ～ `SPEC-TG-025` | 闭环 03 Runtime、06 Policy/Approval、04 Memory Knowledge、02 Ticket/Workflow 相关契约。 |
| 07 Observability Audit Admin | `SPEC-TG-026` ～ `SPEC-TG-029` | 补齐 metrics/logs/traces、audit query、outbox poison/admin repair、connector admin lifecycle。 |
| 08 Recovery Scaling Degraded Mode | `SPEC-TG-030` | 实现 crash recovery、backpressure、worker scaling 和 degraded execution control。 |
| 09 Final Verification Release | `SPEC-TG-031` ～ `SPEC-TG-032` | 完成 e2e/contract harness、最终覆盖审计和 release readiness。 |

## LLD 覆盖

| LLD Section | 覆盖 Specs |
|---|---|
| 01-domain-model | `SPEC-TG-004`, `SPEC-TG-006`, `SPEC-TG-012`, `SPEC-TG-014` |
| 02-business-invariants | `SPEC-TG-001`, `SPEC-TG-004`, `SPEC-TG-007`, `SPEC-TG-013`, `SPEC-TG-021`, `SPEC-TG-025` |
| 03-state-machine | `SPEC-TG-002`, `SPEC-TG-004`, `SPEC-TG-008`, `SPEC-TG-010`, `SPEC-TG-017`, `SPEC-TG-019`, `SPEC-TG-029` |
| 04-use-cases | `SPEC-TG-007`, `SPEC-TG-018`, `SPEC-TG-022`, `SPEC-TG-024` |
| 05-api-contracts | `SPEC-TG-005`, `SPEC-TG-006`, `SPEC-TG-014`, `SPEC-TG-018`, `SPEC-TG-020`, `SPEC-TG-022`, `SPEC-TG-029` |
| 06-event-contracts | `SPEC-TG-008`, `SPEC-TG-009`, `SPEC-TG-015`, `SPEC-TG-022`, `SPEC-TG-023`, `SPEC-TG-024`, `SPEC-TG-025` |
| 07-data-model | `SPEC-TG-002`, `SPEC-TG-006`, `SPEC-TG-014`, `SPEC-TG-027` |
| 08-transaction-and-outbox | `SPEC-TG-003`, `SPEC-TG-008`, `SPEC-TG-010`, `SPEC-TG-015`, `SPEC-TG-028` |
| 09-concurrency-and-idempotency | `SPEC-TG-003`, `SPEC-TG-005`, `SPEC-TG-009`, `SPEC-TG-010`, `SPEC-TG-013`, `SPEC-TG-016`, `SPEC-TG-018`, `SPEC-TG-030` |
| 10-failure-handling | `SPEC-TG-016`, `SPEC-TG-017`, `SPEC-TG-019`, `SPEC-TG-028`, `SPEC-TG-030` |
| 11-security | `SPEC-TG-012`, `SPEC-TG-014`, `SPEC-TG-020`, `SPEC-TG-021`, `SPEC-TG-024` |
| 12-observability | `SPEC-TG-003`, `SPEC-TG-019`, `SPEC-TG-026`, `SPEC-TG-027`, `SPEC-TG-030` |
| 13-package-and-class-design | `SPEC-TG-001`, `SPEC-TG-011` |
| 14-testing-strategy | `SPEC-TG-011`, `SPEC-TG-022`, `SPEC-TG-023`, `SPEC-TG-024`, `SPEC-TG-031`, `SPEC-TG-032` |

## 与 03 Agent Runtime 的闭环

- `SPEC-TG-005`：Runtime 创建、查询、取消 Tool Request。
- `SPEC-TG-015`：Gateway 发布 `tool.completed.v1`，Runtime 幂等恢复等待工具结果的 workflow。
- `SPEC-TG-022`：锁定 03/05 API 与 event contract，禁止 Agent 直连 Tool。

## 与 06 Policy Approval 的闭环

- `SPEC-TG-007`：接入 policy/risk decision。
- `SPEC-TG-008`：需要审批时发布 `tool.approval.required.v1` 并保存 linkage。
- `SPEC-TG-009` / `SPEC-TG-023`：消费 granted/denied 并验证审批契约。

## 与 04 Memory Knowledge 的闭环

- `SPEC-TG-014`：result 标准化与 redaction。
- `SPEC-TG-020`：raw output 受控访问，secret 不进入 memory。
- `SPEC-TG-024`：tool evidence refs 可被 Memory Knowledge 消费。

## 与 02 Ticket Workflow 的闭环

- `SPEC-TG-025`：所有工具执行可追溯 ticket/cycle，但不直接修改 Ticket state。
- `SPEC-TG-015`：工具结果先回 Runtime，再由 Ticket Workflow 根据事实事件和验证结果决定业务迁移。

## 最终完成标准

到 `SPEC-TG-032` 结束时，必须证明：

- 05 的 14 个 LLD 切面都有 spec 覆盖；
- Agent 不能绕过 Gateway 直接执行工具；
- 高风险工具不能绕过 policy/approval；
- connector side effect 幂等、防重、可对账；
- 凭据和 raw output 不泄漏；
- 03/04/06/02 的关键契约可运行并有测试入口；
- crash recovery、outbox replay、poison handling 和 release readiness 完成。
