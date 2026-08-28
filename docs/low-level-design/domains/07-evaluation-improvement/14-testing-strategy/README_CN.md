# 14 Testing Strategy

## 测试目标

测试必须证明：

- 07 不直接修改生产 Agent、Ticket、Workflow、Tool、Policy 或 Memory。
- Dataset version、run、grader bundle 和 report 可复现。
- 安全门禁不能被 LLM Judge、成本优化或人工误操作绕过。
- Improvement candidate 必须经过 benchmark、06 approval、Canary 才能 promotion。
- LangSmith/runner/grader/outbox failure 可恢复且不会产生错误发布。

## Unit Tests

- dataset publish immutability；
- test case ground truth validation；
- run state transition；
- deterministic grader scoring；
- release gate pass/fail；
- regression comparator；
- candidate lifecycle；
- canary threshold evaluator；
- idempotency key conflict。

## Integration Tests

- PostgreSQL schema、唯一键和 optimistic locking；
- run create + audit + outbox 同事务；
- score batch write 重试；
- regression report finalization；
- outbox publish/replay；
- processed event 去重；
- LangSmith adapter failure fallback。

## Contract Tests

- 与 03：Agent Runtime evaluation execution API 和 workflow trace shape；
- 与 02：ticket resolved/reopened event；
- 与 04：memory retrieval trace contract；
- 与 05：tool execution result envelope；
- 与 06：approval request 和 approval decision contract；
- 与 08：metrics/logs/traces semantic fields。

## Security Tests

- viewer 不能读取 sensitive evidence；
- author 不能 publish 自己未 review 的 dataset；
- candidate creator 不能 approve 自己 candidate；
- raw secret 不进入 dataset/report/log；
- forbidden tool 与 policy violation 必须 gate failed；
- service identity 缺失时写 API 被拒绝。

## Recovery Tests

- run worker crash 后恢复；
- scoring 中断后重放；
- outbox publish 后崩溃；
- poison event repair/replay；
- baseline run 缺失；
- stale case result；
- canary rollback event 重复投递。

## Golden Dataset Tests

MVP 至少维护 30-50 个 Identity/MFA 场景，并覆盖：

- Duo enrollment expired；
- Okta session invalid；
- account locked；
- wrong group membership；
- incomplete user description；
- misleading symptom；
- required approval；
- forbidden reset；
- duplicate event；
- enterprise API failure。

## Acceptance Criteria

进入实现 phase 前，07 LLD 必须能支撑：

- 离线 benchmark；
- release gate；
- improvement candidate；
- 06 approval；
- Canary 与 rollback；
- 线上抽样评估；
- traceable audit/report。

