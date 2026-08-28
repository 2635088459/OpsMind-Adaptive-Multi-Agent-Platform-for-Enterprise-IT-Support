# 07 Evaluation Improvement Phase / Spec Coverage Matrix

## 目标

本矩阵用于确认 `07-evaluation-improvement` 的 phase/spec 拆分覆盖 LLD 14 个切面，并且能与 `01-user-access-authentication`、`02-ticket-workflow`、`03-agent-runtime-orchestration`、`04-memory-knowledge`、`05-tool-integration-gateway`、`06-policy-approval-governance`、`08-observability-platform` 闭环协作。

## Phase / Spec 总览

| Phase | Specs | 闭环目标 |
|---|---|---|
| 00 Engineering Foundation | `SPEC-EI-001` ～ `SPEC-EI-003` | 建立 evaluation-improvement-service 的服务边界、schema baseline、outbox/processed-event/audit baseline。 |
| 01 Dataset And Test Assets | `SPEC-EI-004` ～ `SPEC-EI-008` | 实现 EvaluationDataset/TestCase、dataset version、golden dataset、case review/publish 和 artifact lineage。 |
| 02 Benchmark Run And Runner | `SPEC-EI-009` ～ `SPEC-EI-013` | 实现 EvaluationRun、case runner、Agent Runtime evaluation endpoint 调用、LangSmith experiment linkage 和 run lifecycle。 |
| 03 Graders And Scoring | `SPEC-EI-014` ～ `SPEC-EI-018` | 实现 deterministic grader、LLM Judge、grader registry、score persistence 和 judge calibration。 |
| 04 Regression And Release Gate | `SPEC-EI-019` ～ `SPEC-EI-022` | 实现 baseline comparison、gate policy、critical case enforcement、regression report 和 CI gate。 |
| 05 Improvement Candidate Lifecycle | `SPEC-EI-023` ～ `SPEC-EI-026` | 实现 failure clustering、candidate generation、benchmark binding、06 approval request 和 candidate finality。 |
| 06 Canary And Controlled Release | `SPEC-EI-027` ～ `SPEC-EI-029` | 实现 canary plan、online sample evaluation、promotion criteria 和 rollback request。 |
| 07 Cross Domain Contracts | `SPEC-EI-030` ～ `SPEC-EI-033` | 闭环 02 Ticket、03 Runtime、04 Memory、05 Tool Gateway、06 Policy Approval 和 08 Observability 的评估契约。 |
| 08 Security Observability Recovery | `SPEC-EI-034` ～ `SPEC-EI-035` | 补齐 RBAC/ABAC、redaction、metrics/logs/traces、LangSmith outage、poison event 和 recovery。 |
| 09 Final Verification Release | `SPEC-EI-036` | 完成 evaluation contract/e2e harness、最终覆盖审计、release readiness 和剩余风险登记。 |

## LLD 覆盖

| LLD Section | 覆盖 Specs |
|---|---|
| 01-domain-model | `SPEC-EI-004`, `SPEC-EI-005`, `SPEC-EI-009`, `SPEC-EI-017`, `SPEC-EI-019`, `SPEC-EI-024`, `SPEC-EI-027` |
| 02-business-invariants | `SPEC-EI-001`, `SPEC-EI-006`, `SPEC-EI-014`, `SPEC-EI-015`, `SPEC-EI-020`, `SPEC-EI-025`, `SPEC-EI-029`, `SPEC-EI-034`, `SPEC-EI-036` |
| 03-state-machine | `SPEC-EI-002`, `SPEC-EI-004`, `SPEC-EI-009`, `SPEC-EI-024`, `SPEC-EI-027` |
| 04-use-cases | `SPEC-EI-011`, `SPEC-EI-020`, `SPEC-EI-023`, `SPEC-EI-028`, `SPEC-EI-030` |
| 05-api-contracts | `SPEC-EI-008`, `SPEC-EI-010`, `SPEC-EI-012`, `SPEC-EI-021`, `SPEC-EI-026`, `SPEC-EI-030`, `SPEC-EI-032` |
| 06-event-contracts | `SPEC-EI-003`, `SPEC-EI-021`, `SPEC-EI-026`, `SPEC-EI-029`, `SPEC-EI-030`, `SPEC-EI-031`, `SPEC-EI-032`, `SPEC-EI-033` |
| 07-data-model | `SPEC-EI-002`, `SPEC-EI-005`, `SPEC-EI-007`, `SPEC-EI-009`, `SPEC-EI-013`, `SPEC-EI-017`, `SPEC-EI-021`, `SPEC-EI-024` |
| 08-transaction-and-outbox | `SPEC-EI-003`, `SPEC-EI-006`, `SPEC-EI-017`, `SPEC-EI-021`, `SPEC-EI-025`, `SPEC-EI-029`, `SPEC-EI-035` |
| 09-concurrency-and-idempotency | `SPEC-EI-003`, `SPEC-EI-007`, `SPEC-EI-010`, `SPEC-EI-011`, `SPEC-EI-019`, `SPEC-EI-027`, `SPEC-EI-035` |
| 10-failure-handling | `SPEC-EI-011`, `SPEC-EI-013`, `SPEC-EI-016`, `SPEC-EI-018`, `SPEC-EI-028`, `SPEC-EI-029`, `SPEC-EI-035` |
| 11-security | `SPEC-EI-006`, `SPEC-EI-008`, `SPEC-EI-015`, `SPEC-EI-026`, `SPEC-EI-028`, `SPEC-EI-031`, `SPEC-EI-034` |
| 12-observability | `SPEC-EI-003`, `SPEC-EI-013`, `SPEC-EI-018`, `SPEC-EI-022`, `SPEC-EI-023`, `SPEC-EI-028`, `SPEC-EI-033`, `SPEC-EI-034` |
| 13-package-and-class-design | `SPEC-EI-001`, `SPEC-EI-014` |
| 14-testing-strategy | `SPEC-EI-012`, `SPEC-EI-018`, `SPEC-EI-022`, `SPEC-EI-030`, `SPEC-EI-031`, `SPEC-EI-032`, `SPEC-EI-033`, `SPEC-EI-035`, `SPEC-EI-036` |

## 与 01 User Access Authentication 的闭环

- `SPEC-EI-008`：Dataset API 访问控制依赖 actor/tenant/role claims。
- `SPEC-EI-026` / `034`：candidate approval、sensitive evidence access 和 admin repair 必须使用 01 的 identity context。

## 与 02 Ticket Workflow 的闭环

- `SPEC-EI-030`：消费 `ticket.resolved.v1`、`ticket.reopened.v1`、SLA 和用户反馈信号。
- 07 不修改 Ticket state，只评估 resolution success、reopen rate 和用户体验质量。

## 与 03 Agent Runtime 的闭环

- `SPEC-EI-012`：调用 Runtime evaluation endpoint 执行 offline case。
- `SPEC-EI-030`：消费 workflow trace、agent task、handoff 和 terminal events。
- 07 不推进 Workflow state，只评估 workflow trajectory。

## 与 04 Memory Knowledge 的闭环

- `SPEC-EI-031`：消费 retrieval trace、provenance、ACL 和 memory candidate signal。
- 07 不写 Memory 内容，只评估 retrieval precision、evidence grounding 和 unauthorized memory access。

## 与 05 Tool Integration Gateway 的闭环

- `SPEC-EI-031`：消费 tool execution result envelope、tool argument schema、forbidden tool 和 side-effect verification signal。
- 07 不执行 Tool，也不管理 Connector。

## 与 06 Policy Approval Governance 的闭环

- `SPEC-EI-026` / `032`：candidate release approval 必须进入 06。
- `SPEC-EI-015` / `020`：policy violation、required approval、approval denied/expired/cancelled 必须作为 hard gate。

## 与 08 Observability Platform 的闭环

- `SPEC-EI-033` / `034`：输出 evaluation metrics、logs、traces、alerts 和 dashboard semantic fields。
- 08 可展示趋势和告警，但 07 保留 evaluation result 的事实所有权。

## 最终完成标准

到 `SPEC-EI-036` 结束时，必须证明：

- 07 的 14 个 LLD 切面都有 spec 覆盖；
- `identity-mfa-golden-dataset` 可创建、发布、复跑；
- offline benchmark、grader、regression report 和 release gate 可在 CI 中运行；
- safety hard gates 不能被 LLM Judge、成本优化或人工误操作绕过；
- improvement candidate 不能绕过 benchmark、06 approval、Canary 和 rollback guard；
- 02/03/04/05/06/08 的关键 evaluation 契约可运行并有测试入口；
- LangSmith outage、partial run、poison event、outbox replay、admin repair 和 release readiness 完成。

