# 14 Testing Strategy

## 测试目标

测试必须证明：

- 06 不执行业务副作用；
- policy decision 可解释、可复现、可版本追溯；
- approval grant/deny/expire/cancel 幂等；
- 职责分离不可绕过；
- 下游可幂等消费 approval/policy events。

## Unit Tests

- policy rule evaluation；
- risk level mapping；
- approval state transition；
- separation-of-duties check；
- decision idempotency conflict；
- policy version immutability；
- override scope/expiry。

## Integration Tests

- PostgreSQL schema 和唯一键；
- decision + audit + outbox 同事务；
- approval grant/deny 并发冲突；
- expiry worker；
- outbox replay；
- processed event 去重。

## Contract Tests

- 与 05：`tool.approval.required.v1`、`approval.granted.v1`、`approval.denied.v1`；
- 与 03：workflow approval required / granted；
- 与 02：ticket approval required / granted；
- 与 04：retention/redaction decision shape。

## Security Tests

- requester 不能审批自己的请求；
- 未授权 approver 被拒绝；
- policy author 不能发布自己的未审规则；
- audit API 不泄漏敏感 input；
- override 必须有独立审批。

## Recovery Tests

- outbox publish 后崩溃；
- approval decision 事务提交前崩溃；
- policy cache 恢复；
- evaluator failure fail closed；
- duplicate approval events。

## Acceptance Criteria

进入 phase/spec 前，06 LLD 必须证明 14 个切面完整，并且能支撑 05 的 policy/approval 依赖闭环。

