# Ticket Workflow Final Coverage Audit

> **2026-09-01 更正**：本审计当时（2026-08-10）的结论（"Implementation
> closure: not yet fully complete"）是准确的，但现已过时。2026-09-01
> 的一次真实调查（安装 JDK 21，实跑 `./mvnw test`）结果是
> **`BUILD SUCCESS`，2039 个测试，0 失败，0 错误**，覆盖全部 41 个
> spec，包括本审计当时标记为不完整/中断的部分（`SPEC-TW-031-escalate-ticket`
> 的"中断"代码已在本审计写成后的下一个 commit 里修好，2026-08-11 —
> `TicketEscalateTest` 的 25 个测试全部通过）。本审计"Known Gaps"里提到
> "still require implementation contracts, tests, and release gates" 的
> phase 09-10（安全加固、reconciliation/replay/correction/compensation/
> integrity-repair）也确认有真实、对应的实现类与测试类。本审计正确指出的
> 真实缺口（可追溯性闭环，而非实现闭环）现已补齐：
> `docs/traceability/02-ticket-workflow/traceability-matrix.yaml`（全部 41
> 个 spec）及每个 spec 自己的 `traceability-entry.yaml`。本文档仍保留在下方
> 作为历史记录 — 请将其读作"2026-08-10 当时的真实状态"，而非当前状态。
>
> Domain：`02-ticket-workflow`
>
> Audit Date：2026-08-10
>
> Baseline：`docs/low-level-design/domains/02-ticket-workflow/`
>
> Roadmap：`docs/implementation-plans/domains/02-ticket-workflow/00-implementation-roadmap_CN(1).md`
>
> Specs：`docs/specs/domains/02-ticket-workflow/SPEC-TW-001` ～ `SPEC-TW-041`
>
> Status：Design Coverage Review

## 1. 结论

Ticket Workflow 的设计层覆盖已经基本闭合。

当前文档集已经覆盖：

- 14 组冻结 Low-Level Design；
- Phase 00 ～ Phase 10；
- `SPEC-TW-001` ～ `SPEC-TW-041`；
- Ticket 主生命周期；
- query/message/timeline；
- lifecycle/ownership；
- waiting-for-user；
- approval/policy；
- tool execution result；
- verification/resolution；
- close/reopen/cancel/assign/escalate；
- security/audit/operational hardening；
- reconciliation/replay/correction/compensation/integrity repair。

但本审计不声明“代码实现 100% 完成”。原因：

- `SPEC-TW-001` ～ `SPEC-TW-006` 的文件颗粒度与后续 18 文件 spec 模板不一致；
- 部分 LLD API 被合并进更大的 spec，尚需在 traceability matrix 中逐项标注；
- `SPEC-TW-031-escalate-ticket` 的代码实现曾被中断，需要单独收口；
- Phase 09/10 是 hardening/recovery 层，后续实现时仍需补 contract、test 和 release gate。

因此最终判定是：

```text
Design coverage: substantially complete
Traceability closure: requires final matrix update
Implementation closure: not yet fully complete
Ready to design 03-agent-runtime-orchestration: yes, after Ticket Workflow cleanup checklist
```

## 2. Phase 覆盖矩阵

| Phase | 范围 | Specs | 覆盖状态 | 备注 |
|---|---|---|---|---|
| Phase 00 | Engineering Foundation | 无业务 spec | Covered | 构建、测试、配置、安全基础、数据库/消息基础 |
| Phase 01 | Create Ticket | `SPEC-TW-001` | Covered | Ticket aggregate、initial SLA/resolution cycle、created event |
| Phase 02 | Query and Message | `SPEC-TW-002` ～ `SPEC-TW-006` | Covered | get/list/message/support queue/timeline |
| Phase 03 | Lifecycle and Ownership | `SPEC-TW-007` ～ `SPEC-TW-011` | Covered | triage、assign、transition、resolve、close/reopen |
| Phase 04 | Waiting for User | `SPEC-TW-012` ～ `SPEC-TW-013` | Covered | request-user-input、reply/resume |
| Phase 05 | Policy and Approval | `SPEC-TW-014` ～ `SPEC-TW-018` | Covered | approval request/granted/rejected/expired/auto-approved |
| Phase 06 | Tool Execution | `SPEC-TW-019` ～ `SPEC-TW-021` | Covered | completed/failed/unknown result |
| Phase 07 | Verification and Resolution | `SPEC-TW-022` ～ `SPEC-TW-025` | Covered | start verification、success/failure、verified resolution |
| Phase 08 | Closure/Reopen/Assignment/Escalation | `SPEC-TW-026` ～ `SPEC-TW-032` | Covered | confirmed close、auto-close、reopen、cancel、assign、escalate、resume |
| Phase 09 | Security/Audit/Operational Hardening | `SPEC-TW-033` ～ `SPEC-TW-036` | Covered | queue auth、sensitive-read audit、secret detection、step-up auth |
| Phase 10 | Reconciliation/Chaos/Release Readiness | `SPEC-TW-037` ～ `SPEC-TW-041` | Covered | reconciliation、replay、correction、compensation、integrity repair |

## 3. LLD 覆盖矩阵

| LLD | 主要关注点 | Covered By | 覆盖判断 |
|---|---|---|---|
| `01-domain-model` | Ticket、Message、Resolution Cycle、SLA Cycle、Assignment、Audit、Outbox | `SPEC-TW-001`～`013`, `026`～`032` | Covered |
| `02-business-invariants` | 状态约束、唯一 active cycle、不可绕过验证、不可重复执行 | 全部业务 specs，尤其 `001`, `009`, `014`～`025`, `029`～`032` | Covered |
| `03-state-machine` | Ticket 状态、合法转换、异常状态 | `SPEC-TW-007`～`032` | Covered |
| `04-use-cases` | 创建、查询、消息、triage、等待、审批、执行、验证、关闭、恢复 | `SPEC-TW-001`～`041` | Covered |
| `05-api-contracts` | public/support/internal APIs、headers、error envelope | `SPEC-TW-001`～`041` | Mostly covered |
| `06-event-contracts` | ticket events、consumed events、outbox payload | `SPEC-TW-001`, `007`～`041` | Covered |
| `07-data-model` | tables、indexes、constraints、history/audit/outbox/idempotency | `SPEC-TW-001`～`041` persistence docs | Covered |
| `08-transaction-and-outbox` | transactional outbox、consumer transaction、crash windows | `SPEC-TW-001`, `014`～`025`, `037`～`041` | Covered |
| `09-concurrency-and-idempotency` | idempotency key、request hash、version conflict、duplicate event | all command/event specs | Covered |
| `10-failure-handling` | stale、duplicate、unknown、DLQ、reconciliation | `SPEC-TW-019`～`021`, `024`, `037`～`041` | Covered |
| `11-security` | OAuth/JWT、scope、queue authorization、field visibility、step-up | `SPEC-TW-002`, `005`, `033`, `036` | Covered |
| `12-observability` | logs、metrics、trace、audit records、alerts | `SPEC-TW-001`～`041`, especially `033`～`041` | Covered |
| `13-package-and-class-design` | package boundaries、ports/adapters、services/controllers | Phase 00 + all implementation specs | Covered by plan; implementation varies |
| `14-testing-strategy` | unit/integration/contract/failure/chaos tests | each spec test plan, Phase 10 release gate | Covered by plan; execution pending |

## 4. API / Use Case 关注点

### Fully Mapped

- Create Ticket：`SPEC-TW-001`
- Get Ticket：`SPEC-TW-002`
- List Requester Tickets：`SPEC-TW-003`
- Add Ticket Message：`SPEC-TW-004`
- Support Queue Query：`SPEC-TW-005`
- Ticket Timeline：`SPEC-TW-006`
- Triage Ticket：`SPEC-TW-007`
- Assign Ticket：`SPEC-TW-008`, `SPEC-TW-030`
- Transition Ticket Status：`SPEC-TW-009`
- Resolve Ticket：`SPEC-TW-010`, `SPEC-TW-025`
- Close/Reopen：`SPEC-TW-011`, `SPEC-TW-026`～`028`
- Request User Input / Reply：`SPEC-TW-012`～`013`
- Approval lifecycle：`SPEC-TW-014`～`018`
- Tool result lifecycle：`SPEC-TW-019`～`021`
- Verification lifecycle：`SPEC-TW-022`～`025`
- Cancel/Escalate/Resume：`SPEC-TW-029`, `031`, `032`
- Security hardening：`SPEC-TW-033`～`036`
- Recovery/release readiness：`SPEC-TW-037`～`041`

### Needs Explicit Traceability Annotation

以下 LLD API/用例已经被设计覆盖，但需要在最终 traceability matrix 中显式写出映射：

- List Ticket Messages：主要由 `SPEC-TW-004` / `SPEC-TW-006` 覆盖；
- Start Triage：由 `SPEC-TW-007` 覆盖；
- Complete Classification：由 `SPEC-TW-007` 覆盖；
- Associate Active Workflow：Ticket 侧由 `SPEC-TW-009` / `SPEC-TW-019`～`021` 预留，完整 runtime 归 `03-agent-runtime-orchestration`；
- Get Internal Ticket Context：由 `SPEC-TW-002` 查询模型与 Agent Runtime 后续 integration spec 共同覆盖；
- Retry Failed Automation：Ticket 侧由 `SPEC-TW-020` / `SPEC-TW-021` / `SPEC-TW-037`～`041` 覆盖，主动 retry 编排归 Agent Runtime；
- SLA Breach Enforcement：Ticket 创建和 query 已有 SLA summary/cycle，完整 breach engine 可作为后续 SLA/Policy/Observability integration。

## 5. 已知 Gap / 风险

| Gap | 影响 | 建议 |
|---|---|---|
| `SPEC-TW-001`～`006` 文件颗粒度不统一 | 文档 review 体验不一致 | 可后续补齐到 18 文件模板，但不阻塞进入 Agent Runtime |
| Traceability matrix 未覆盖 `SPEC-TW-001`～`041` 全量映射 | 无法正式宣称 100% traceability closure | 更新 matrix，将 LLD/API/Event/Invariant 映射到 Phase/Spec |
| `SPEC-TW-031-escalate-ticket` 实现曾中断 | 代码可能处于部分修改状态 | 在继续代码实现前检查 git diff 并闭环 SPEC-TW-031 |
| Phase 09/10 为 hardening/recovery 文档 | 需要真实测试验证 | 实现阶段必须加入 security、failure、chaos、release gate tests |
| Agent Runtime 相关边界被 Ticket 侧预留 | 不能在 Ticket Workflow 内实现 agent state | 在 `03-agent-runtime-orchestration` LLD 中正式定义 Workflow Instance、Task、Checkpoint、Pause/Resume |

## 6. 进入 03-agent-runtime-orchestration 前的收口清单

- [ ] 确认 `SPEC-TW-031-escalate-ticket` 的代码修改是否继续完成或回滚到稳定状态。
- [ ] 跑 Ticket Workflow 核心 test suite，至少覆盖 create/query/message/lifecycle/approval/tool/verification/closure。
- [ ] 更新 traceability matrix，覆盖 `SPEC-TW-001`～`041`。
- [ ] 标注哪些 specs 是文档完成、哪些是代码完成、哪些是 pending implementation。
- [ ] 明确 `Associate Active Workflow`、`Retry Failed Automation`、`Get Internal Ticket Context` 与 Agent Runtime 的边界。
- [ ] 决定是否补齐 `SPEC-TW-001`～`006` 的 18 文件模板。

## 7. 最终判定

Ticket Workflow 的设计覆盖已经足够支撑进入下一个领域：

```text
Next Domain: 03-agent-runtime-orchestration
Next Step: generate full LLD baseline for Agent Runtime before phase/spec split
```

但进入前建议完成最小收口：

```text
SPEC-TW-031 code cleanup
Traceability matrix update
Core test verification
```

完成这三项后，可以把 Ticket Workflow 标记为：

```text
Design: Closed
Implementation: Partially Closed / Spec-dependent
Ready for Agent Runtime Design: Yes
```
