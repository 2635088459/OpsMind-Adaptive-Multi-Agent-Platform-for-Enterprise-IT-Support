# 06 Event Contracts

## 消费事件

| Event | Producer | 用途 |
|---|---|---|
| `agent.workflow.completed.v1` | 03 | 触发线上抽样评估 |
| `agent.workflow.failed.v1` | 03 | 失败归因与 regression signal |
| `ticket.resolved.v1` | 02 | resolution success 统计 |
| `ticket.reopened.v1` | 02 | reopen rate 与误判检测 |
| `tool.execution.completed.v1` | 05 | tool selection/argument/side effect 评分 |
| `tool.execution.failed.v1` | 05 | tool failure 分类 |
| `approval.granted.v1` | 06 | 审批摩擦和合规评估 |
| `approval.denied.v1` | 06 | policy-sensitive case 分析 |
| `memory.retrieval.completed.v1` | 04 | retrieval precision/provenance 评分 |

## 发布事件

| Event | Consumers | 语义 |
|---|---|---|
| `evaluation.run.requested.v1` | 07 workers | benchmark run 已创建 |
| `evaluation.run.completed.v1` | CI/Admin/08 | run 完成 |
| `evaluation.gate.passed.v1` | CI/06/03 | release gate 通过 |
| `evaluation.gate.failed.v1` | CI/Admin/08 | release gate 失败 |
| `evaluation.regression.detected.v1` | Admin/08 | 发现质量或安全回归 |
| `improvement.candidate.created.v1` | 06/Admin | 新候选生成 |
| `improvement.candidate.approved.v1` | 03/Config owner | 候选已获准 Canary |
| `improvement.rollback.requested.v1` | 03/Config owner/08 | 需要回滚 |

## Event Envelope 要求

每个事件必须包含：

- `eventId`
- `eventType`
- `eventVersion`
- `occurredAt`
- `producer`
- `traceId`
- `correlationId`
- `runId`
- `candidateId`
- `payload`

PII 分类默认为 `INTERNAL`；包含用户文本或 trace 摘要时必须标记 `CONFIDENTIAL_REDACTED`。

