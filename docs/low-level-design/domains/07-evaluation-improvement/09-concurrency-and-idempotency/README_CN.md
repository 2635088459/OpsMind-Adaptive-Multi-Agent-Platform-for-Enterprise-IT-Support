# 09 Concurrency And Idempotency

## 幂等键

- Dataset publish：`dataset:{name}:{version}`
- Run create：`runKey`
- Test case execution：`runId:testCaseId:attempt`
- Score write：`runId:testCaseId:dimension:graderVersion`
- Candidate create：`sourceRunId:failureClusterId:targetComponent`
- Canary operation：`candidateId:canaryPlanVersion:operation`

## 并发规则

- 同一个 `runKey` 重复提交必须返回同一 run。
- 同一 run 的同一 case 可以重试，但 final score 只能有一个 active version。
- Baseline comparison 必须锁定 baseline run id，不能比较移动中的 latest。
- Candidate promotion 使用 optimistic locking，避免两个审批/Canary 操作同时推进。
- Dataset publish 对 `(name, version)` 加唯一约束。

## 重复事件处理

07 消费外部事件时写 `processed_events`。重复 event 返回已处理结果，不重复生成 online sample 或 candidate。

## Stale 结果

如果 case runner 返回的 `runGeneration` 与当前 run generation 不一致，结果标记为 `STALE_RESULT`，不得进入 gate 计算。

