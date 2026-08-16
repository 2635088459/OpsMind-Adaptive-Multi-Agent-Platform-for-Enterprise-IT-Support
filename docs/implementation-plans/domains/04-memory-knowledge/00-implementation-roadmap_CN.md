# 04 Memory Knowledge Implementation Roadmap

> Domain：Memory Knowledge
>
> Service：`memory-knowledge-service`
>
> 文档状态：Implementation Roadmap

## 1. 总目标

把 04 从 LLD 落成可实现的 phase/spec：既支持 Working Memory、Long-Term Memory、Knowledge Ingestion、Hybrid Retrieval 与 Knowledge Graph，也能与 02 Ticket Workflow 和 03 Agent Runtime 通过事件/API 闭环。

## 2. Phase 总览

| Phase | 名称 | Specs | 目标 |
|---|---|---|---|
| 00 | 工程基础 | `SPEC-MK-001` ～ `SPEC-MK-003` | 建立 memory-knowledge-service 的模块边界、schema baseline、outbox/idempotency/audit baseline。 |
| 01 | Working Memory | `SPEC-MK-004` ～ `SPEC-MK-006` | 实现 ticket/cycle/workflow 范围内的短期上下文存储、merge、查询和归档。 |
| 02 | Knowledge Ingestion | `SPEC-MK-007` ～ `SPEC-MK-009` | 实现正式知识文档导入、parse/chunk/redaction、embedding 和可检索激活。 |
| 03 | Memory Candidate Pipeline | `SPEC-MK-010` ～ `SPEC-MK-013` | 从 02/03 事实事件抽取长期记忆候选，并完成 redaction、validation、dedup、conflict 和 review。 |
| 04 | Versioned Memory Publication | `SPEC-MK-014` ～ `SPEC-MK-016` | 发布 active MemoryVersion，支持 supersession、deprecation、retention 和 deletion。 |
| 05 | Retrieval 与 Knowledge Graph | `SPEC-MK-017` ～ `SPEC-MK-020` | 实现 hybrid retrieval、graph node/edge、bounded graph expansion、provenance 和 degraded retrieval。 |
| 06 | Cross Domain Contracts | `SPEC-MK-021` ～ `SPEC-MK-024` | 闭环 02 Ticket Workflow 与 03 Agent Runtime 的消费/发布/API 契约。 |
| 07 | Security 与 Governance | `SPEC-MK-025` ～ `SPEC-MK-027` | 实现 ACL、classification、PII/secret redaction、prompt injection 和 graph traversal guard。 |
| 08 | Observability Recovery Admin | `SPEC-MK-028` ～ `SPEC-MK-030` | 补齐 metrics/traces/audit、poison/recovery workers、admin repair/reindex/replay。 |
| 09 | Final Verification Release | `SPEC-MK-031` ～ `SPEC-MK-032` | 完成 contract/e2e harness、coverage audit 和 release readiness。 |

## 3. 闭环原则

- 02 仍拥有 Ticket state；04 只消费 ticket fact events。
- 03 仍拥有 Workflow state；04 只提供 memory/search/working-memory 能力。
- 04 不执行 Tool、不批准 Policy、不关闭 Ticket。
- Active long-term memory 必须来自 candidate pipeline，不能由 Agent 直接写入。
- Graph 是检索和解释索引，不是业务状态机。
- 所有消费事件 processed-event 去重，所有发布事件走 outbox。
