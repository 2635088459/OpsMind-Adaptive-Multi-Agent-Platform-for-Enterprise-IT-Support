# Event Contract — SPEC-EI-034

## Event 影响

本 spec 可能消费或发布 07 相关事件，但必须保持以下原则：

- 发布事件必须来自 Evaluation outbox；
- 消费事件必须写 processed event 并可幂等重放；
- event envelope 必须包含 eventId、eventType、eventVersion、occurredAt、producer、traceId、correlationId；
- payload 必须标记 PII classification，且不得包含 raw secret。

## 可能发布的事件

- `evaluation.run.requested.v1`
- `evaluation.run.completed.v1`
- `evaluation.gate.passed.v1`
- `evaluation.gate.failed.v1`
- `evaluation.regression.detected.v1`
- `improvement.candidate.created.v1`
- `improvement.candidate.approved.v1`
- `improvement.rollback.requested.v1`

## 可能消费的事件

- `agent.workflow.completed.v1`
- `agent.workflow.failed.v1`
- `ticket.resolved.v1`
- `ticket.reopened.v1`
- `tool.execution.completed.v1`
- `tool.execution.failed.v1`
- `approval.granted.v1`
- `approval.denied.v1`
- `memory.retrieval.completed.v1`

具体使用的事件由本 spec 的实现范围收敛。
