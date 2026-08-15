# Memory Knowledge LLD

## 范围

本目录定义 `04-memory-knowledge` 的低层设计。该域负责为 Agent Runtime 提供可检索、可追溯、可版本化、可删除的知识与记忆能力。

Memory Knowledge 不拥有 Ticket 状态，不执行 Tool，不做 Policy 决策，也不直接驱动 Workflow 状态迁移。它只回答两个问题：

- 当前 ticket / workflow 中已经确认过哪些事实、假设、证据和上下文？
- 过去经过验证的 ticket、runbook、组织知识和 Agent 经验中，哪些内容可以作为可引用证据提供给 Runtime / Agent？

## 核心回答

- Working Memory 是 ticket/cycle/workflow 范围内的短期上下文，不自动进入长期记忆。
- Long-Term Memory 是经过抽取、脱敏、证据校验、去重、冲突检测和版本化后的长期资产。
- Knowledge Document 是人工或系统导入的正式知识源，例如 runbook、FAQ、SOP、服务目录和故障公告。
- Retrieval Result 必须携带 provenance：来源类型、source id、chunk id、memory version、score、redaction status。
- Memory Candidate 不是 Memory。候选必须经过 validation / dedup / conflict / usefulness scoring 后才能发布为 active memory。
- Agent Runtime 可以查询 Memory / Knowledge，但不能直接写入 active long-term memory；写入必须通过事件和受控 pipeline。
- Agent 不能把检索结果当作最终事实。检索结果是 evidence input，最终 ticket 决策仍由 Ticket Workflow / Policy / Tool / Verification 共同约束。
- PII / secrets / raw tool output 不能进入长期记忆；必要时只保存摘要、哈希、引用和 redacted evidence。
- 删除、retention、supersession 必须可审计，且要同时处理 memory row、versions、embeddings、chunks 和 retrieval visibility。
- 04 与 03 通过 `memory.search` API 和事件相连；与 02 通过 `ticket.resolved` / `ticket.closed` 等事件触发候选抽取。

## 14 个 LLD 切面

1. `01-domain-model`：Working Memory、Memory、Memory Candidate、Knowledge Document、Chunk、Embedding、Retrieval Log。
2. `02-business-invariants`：provenance、no unvalidated memory、PII redaction、state ownership。
3. `03-state-machine`：Memory Candidate、Memory Version、Knowledge Document ingestion、Deletion 状态机。
4. `04-use-cases`：working memory update、knowledge ingestion、search、candidate extraction、validation、retention。
5. `05-api-contracts`：Runtime search API、admin ingestion API、memory candidate review API、deletion API。
6. `06-event-contracts`：消费 ticket/workflow/evaluation 事件，发布 memory/knowledge 事件。
7. `07-data-model`：PostgreSQL + pgvector 表、索引、唯一键、retention 字段。
8. `08-transaction-and-outbox`：candidate 写入、active memory 发布、embedding 生成、outbox 顺序。
9. `09-concurrency-and-idempotency`：并发写 memory、重复事件、document reingestion、search log 去重。
10. `10-failure-handling`：embedding failure、poison document、partial ingestion、degraded retrieval。
11. `11-security`：PII/secret redaction、tenant/role filter、knowledge ACL、audit。
12. `12-observability`：retrieval precision、hit rate、index lag、candidate acceptance rate、trace。
13. `13-package-and-class-design`：service package、ports、adapters、repository、pipeline。
14. `14-testing-strategy`：unit、integration、contract、retrieval quality、security、recovery tests。

## 与其他域的关系

- `02-ticket-workflow`：产生 resolved/closed/reopened 等事实事件；04 只读取这些事实，不改变 ticket lifecycle。
- `03-agent-runtime-orchestration`：调用 search / working-memory API 构建 Agent context；04 不推进 workflow state。
- `05-tool-gateway-mediation`：Tool Gateway 可以把工具结果作为 evidence source 传入 04，但 04 不执行工具。
- `06-policy-approval-governance`：04 调用 policy / redaction / retention rule，不自行批准高风险记忆。
- `07-evaluation-improvement`：评估结果可影响 memory usefulness score，但不能绕过 validation 直接改 active memory。
