# 04 Memory Knowledge Phase / Spec Coverage Matrix

## 目标

本矩阵用于确认 `04-memory-knowledge` 的 phase/spec 拆分覆盖 LLD 14 个切面，并且能与 `02-ticket-workflow`、`03-agent-runtime-orchestration` 闭环协作。

## Phase / Spec 总览

| Phase | Specs | 闭环目标 |
|---|---|---|
| 00 Engineering Foundation | `SPEC-MK-001` ～ `SPEC-MK-003` | 服务边界、schema、outbox、processed-events、audit baseline |
| 01 Working Memory | `SPEC-MK-004` ～ `SPEC-MK-006` | 03 Runtime 可保存/读取 ticket-scoped 短期上下文 |
| 02 Knowledge Ingestion | `SPEC-MK-007` ～ `SPEC-MK-009` | 正式文档可导入、脱敏、切片、embedding、激活检索 |
| 03 Memory Candidate Pipeline | `SPEC-MK-010` ～ `SPEC-MK-013` | 02/03 事实事件可生成受控候选记忆 |
| 04 Versioned Memory Publication | `SPEC-MK-014` ～ `SPEC-MK-016` | 候选可发布为 active memory，并支持 supersede/delete |
| 05 Retrieval And Knowledge Graph | `SPEC-MK-017` ～ `SPEC-MK-020` | Runtime 可获得 hybrid retrieval + graph path + provenance |
| 06 Cross Domain Contracts | `SPEC-MK-021` ～ `SPEC-MK-024` | 锁定 02->04、03->04、04->下游、03 client API 契约 |
| 07 Security And Governance | `SPEC-MK-025` ～ `SPEC-MK-027` | ACL、PII/secret、prompt injection、graph traversal guard |
| 08 Observability Recovery Admin | `SPEC-MK-028` ～ `SPEC-MK-030` | metrics/traces/audit、poison/recovery、admin repair |
| 09 Final Verification Release | `SPEC-MK-031` ～ `SPEC-MK-032` | contract/e2e harness、最终覆盖审计和 release readiness |

## LLD 覆盖

| LLD Section | 覆盖 Specs |
|---|---|
| 01-domain-model | `SPEC-MK-004`, `007`, `011`, `014`, `018` |
| 02-business-invariants | `SPEC-MK-001`, `004`, `012`, `018`, `025` |
| 03-state-machine | `SPEC-MK-006`, `012`, `014`, `016`, `018` |
| 04-use-cases | `SPEC-MK-005`, `008`, `011`, `017`, `019` |
| 05-api-contracts | `SPEC-MK-005`, `006`, `007`, `013`, `017`, `019`, `024`, `030` |
| 06-event-contracts | `SPEC-MK-010`, `021`, `022`, `023` |
| 07-data-model | `SPEC-MK-002`, `009`, `014`, `017`, `018`, `020` |
| 08-transaction-and-outbox | `SPEC-MK-003`, `009`, `015`, `018`, `023` |
| 09-concurrency-and-idempotency | `SPEC-MK-003`, `005`, `010`, `012`, `018`, `020` |
| 10-failure-handling | `SPEC-MK-009`, `020`, `029`, `030` |
| 11-security | `SPEC-MK-008`, `013`, `025`, `026`, `027` |
| 12-observability | `SPEC-MK-003`, `020`, `028`, `030` |
| 13-package-and-class-design | `SPEC-MK-001`, `018`, `024` |
| 14-testing-strategy | `SPEC-MK-031`, `032` |

## 与 02 Ticket Workflow 的闭环

- `SPEC-MK-010`：消费 `ticket.resolved.v1` / `ticket.closed.v1`，开始 candidate extraction。
- `SPEC-MK-021`：锁定 02 outbox envelope 兼容性、重复投递和非法 payload 行为。
- `SPEC-MK-011` ～ `013`：从 ticket facts 生成候选，但不直接修改 Ticket state。
- `SPEC-MK-023`：发布 memory events 给 evaluation / analytics，不要求 02 直接改变 Ticket state。

## 与 03 Agent Runtime 的闭环

- `SPEC-MK-004` ～ `006`：03 可写/读 Working Memory，但不能写 active long-term memory。
- `SPEC-MK-017` ～ `020`：03 可调用 search，得到 evidence、graph path 和 provenance。
- `SPEC-MK-022`：消费 `workflow.completed.v1` / `workflow.failed.v1` 作为 automation trace/evidence。
- `SPEC-MK-024`：锁定 03 MemoryClient API，确保 degraded mode、ACL、graph path shape 可测试。

## Graph 闭环

- `SPEC-MK-018`：graph_nodes / graph_edges 的模型、表、upsert。
- `SPEC-MK-019`：bounded graph expansion、rerank、provenance response。
- `SPEC-MK-020`：graph degraded mode 和 retrieval log。
- `SPEC-MK-027`：graph traversal guard，禁止跨 ACL/classification 或把 graph path 当作执行计划。

## 最终完成标准

到 `SPEC-MK-032` 结束时，必须证明：

- 04 的 14 个 LLD 切面都有 spec 覆盖；
- 02->04 ticket event contract 可运行；
- 03->04 workflow/search/client contract 可运行；
- active memory 只能由 candidate pipeline 发布；
- retrieval result 必须包含 provenance；
- graph path 可解释且受限；
- 删除、retention、redaction、audit、recovery 都有测试计划和实现入口。
