# 06 Policy Approval Governance Phase / Spec Coverage Matrix

## 目标

本矩阵用于确认 `06-policy-approval-governance` 的 phase/spec 拆分覆盖 LLD 14 个切面，并且能与 `02-ticket-workflow`、`03-agent-runtime-orchestration`、`04-memory-knowledge`、`05-tool-integration-gateway` 闭环协作。

## Phase / Spec 总览

| Phase | Specs | 闭环目标 |
|---|---|---|
| 00 Engineering Foundation | `SPEC-PG-001` ～ `SPEC-PG-003` | 建立 policy-approval-governance-service 的服务边界、schema baseline、outbox/processed-event/audit baseline。 |
| 01 Policy Model And Decision Engine | `SPEC-PG-004` ～ `SPEC-PG-008` | 实现 Policy/Rule/Version 模型、decision API、rule evaluator、risk mapping 和 constraints 输出。 |
| 02 Approval Lifecycle | `SPEC-PG-009` ～ `SPEC-PG-013` | 实现 ApprovalRequest、grant/deny/cancel/expire、approval decision finality 和事件发布。 |
| 03 Security Separation Of Duties | `SPEC-PG-014` ～ `SPEC-PG-017` | 实现 RBAC/ABAC、职责分离、approval authenticity、MFA/step-up marker 和 override guard。 |
| 04 Policy Admin And Versioning | `SPEC-PG-018` ～ `SPEC-PG-021` | 实现 policy draft/review/publish/deprecate/supersede、版本不可变和 policy cache 刷新。 |
| 05 Override And Exception Governance | `SPEC-PG-022` ～ `SPEC-PG-024` | 实现高风险 override、SLA/ticket exception、admin repair approval 和范围化撤销。 |
| 06 Cross Domain Contracts | `SPEC-PG-025` ～ `SPEC-PG-028` | 闭环 05 Tool Gateway、03 Agent Runtime、02 Ticket Workflow、04 Memory Knowledge 的 governance 契约。 |
| 07 Observability Audit Compliance | `SPEC-PG-029` ～ `SPEC-PG-031` | 补齐 metrics/logs/traces、governance audit query、audit integrity 和 compliance reporting。 |
| 08 Failure Recovery Degraded Mode | `SPEC-PG-032` ～ `SPEC-PG-034` | 实现 evaluator failure、approval expiry worker、poison decision、outbox replay 和 fail-closed degraded mode。 |
| 09 Final Verification Release | `SPEC-PG-035` ～ `SPEC-PG-036` | 完成跨域 contract/e2e harness、最终覆盖审计、release readiness 和剩余风险登记。 |

## LLD 覆盖

| LLD Section | 覆盖 Specs |
|---|---|
| 01-domain-model | `SPEC-PG-004`, `SPEC-PG-005`, `SPEC-PG-009`, `SPEC-PG-022` |
| 02-business-invariants | `SPEC-PG-001`, `SPEC-PG-004`, `SPEC-PG-008`, `SPEC-PG-015`, `SPEC-PG-019`, `SPEC-PG-024` |
| 03-state-machine | `SPEC-PG-002`, `SPEC-PG-005`, `SPEC-PG-009`, `SPEC-PG-011`, `SPEC-PG-012`, `SPEC-PG-018`, `SPEC-PG-020`, `SPEC-PG-022` |
| 04-use-cases | `SPEC-PG-007`, `SPEC-PG-022`, `SPEC-PG-023`, `SPEC-PG-027` |
| 05-api-contracts | `SPEC-PG-006`, `SPEC-PG-010`, `SPEC-PG-011`, `SPEC-PG-016`, `SPEC-PG-018`, `SPEC-PG-025`, `SPEC-PG-028`, `SPEC-PG-030` |
| 06-event-contracts | `SPEC-PG-010`, `SPEC-PG-013`, `SPEC-PG-020`, `SPEC-PG-021`, `SPEC-PG-023`, `SPEC-PG-025`, `SPEC-PG-026`, `SPEC-PG-027`, `SPEC-PG-028` |
| 07-data-model | `SPEC-PG-002`, `SPEC-PG-005`, `SPEC-PG-009`, `SPEC-PG-030` |
| 08-transaction-and-outbox | `SPEC-PG-003`, `SPEC-PG-010`, `SPEC-PG-012`, `SPEC-PG-013`, `SPEC-PG-033` |
| 09-concurrency-and-idempotency | `SPEC-PG-003`, `SPEC-PG-006`, `SPEC-PG-008`, `SPEC-PG-011`, `SPEC-PG-034` |
| 10-failure-handling | `SPEC-PG-007`, `SPEC-PG-012`, `SPEC-PG-021`, `SPEC-PG-024`, `SPEC-PG-032`, `SPEC-PG-033`, `SPEC-PG-034` |
| 11-security | `SPEC-PG-014`, `SPEC-PG-015`, `SPEC-PG-016`, `SPEC-PG-017`, `SPEC-PG-018`, `SPEC-PG-022`, `SPEC-PG-024`, `SPEC-PG-028`, `SPEC-PG-031` |
| 12-observability | `SPEC-PG-003`, `SPEC-PG-017`, `SPEC-PG-029`, `SPEC-PG-030`, `SPEC-PG-031`, `SPEC-PG-032` |
| 13-package-and-class-design | `SPEC-PG-001` |
| 14-testing-strategy | `SPEC-PG-025`, `SPEC-PG-026`, `SPEC-PG-027`, `SPEC-PG-028`, `SPEC-PG-035`, `SPEC-PG-036` |

## 与 05 Tool Gateway 的闭环

- `SPEC-PG-006`：提供 risk/approval decision API。
- `SPEC-PG-010` / `013`：处理 approval request 并发布 granted/denied/expired/cancelled。
- `SPEC-PG-025`：锁定 05/06 契约，确保高风险工具不可绕过审批。

## 与 03 Agent Runtime 的闭环

- `SPEC-PG-026`：支持 workflow approval required、automation risk、override governance。
- 06 不推进 Workflow state，只发布 governance facts。

## 与 02 Ticket Workflow 的闭环

- `SPEC-PG-023` / `027`：支持 SLA exception、closure override、escalation exception。
- 06 不修改 Ticket state，只输出 approval/policy facts。

## 与 04 Memory Knowledge 的闭环

- `SPEC-PG-028`：支持 retention、redaction、sensitive retrieval、memory publication policy decision。
- 06 不写 memory 内容，也不保存原始敏感知识。

## 最终完成标准

到 `SPEC-PG-036` 结束时，必须证明：

- 06 的 14 个 LLD 切面都有 spec 覆盖；
- policy decision 可解释、可复现、可追溯 policy version；
- approval lifecycle 幂等且 final decision 唯一；
- 职责分离和 override guard 不能绕过；
- 02/03/04/05 的关键 governance 契约可运行并有测试入口；
- audit、outbox、recovery、degraded mode 和 release readiness 完成。
