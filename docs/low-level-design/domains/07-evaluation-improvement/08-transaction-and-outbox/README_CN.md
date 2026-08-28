# 08 Transaction And Outbox

## 事务原则

- 创建 run 时，`evaluation_runs` 与 `evaluation.run.requested.v1` outbox 必须同事务提交。
- 写 score 时，score 可以按 case 分批提交；每批必须可幂等重放。
- 生成 regression report 时，report、run final status、audit、gate event 必须同事务提交。
- 创建 improvement candidate 时，candidate、audit、candidate created event 必须同事务提交。
- Candidate 状态进入 `PENDING_APPROVAL` 时，必须记录 06 approval request 引用。

## Outbox 发布

```text
Application Transaction
→ write aggregate
→ write audit
→ write outbox
→ commit
→ OutboxPublisher publishes
→ mark published
```

## 不使用跨域事务

07 不与 02/03/04/05/06 做分布式事务。跨域一致性通过：

- event idempotency；
- source reference；
- input hash；
- reconciliation worker；
- audit trace；
- admin replay。

## Run 完成事务

`EvaluationRun` finalization 必须检查：

- 所有 expected case 已有 score 或被标记为 skipped/failed；
- grader bundle version 已冻结；
- gate policy version 已冻结；
- regression report 已生成；
- gate event 已进入 outbox。

