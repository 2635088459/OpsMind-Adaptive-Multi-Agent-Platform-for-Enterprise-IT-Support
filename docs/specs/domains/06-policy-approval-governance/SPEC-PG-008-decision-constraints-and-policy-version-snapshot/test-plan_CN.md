# Test Plan — SPEC-PG-008

## 单元测试

- domain state transition / rule validation；
- decision/approval idempotency conflict；
- forbidden path 不会产生业务副作用；
- reason code、risk level、final status 映射。

## 集成测试

- PostgreSQL 持久化与唯一键；
- audit + outbox 同事务；
- processed event 去重；
- approval 并发 grant/deny；
- evaluator failure 和 expiry worker。

## 契约测试

- 与 05 Tool Gateway 的 risk/approval event shape；
- 与 03 Runtime 的 workflow governance shape；
- 与 02 Ticket Workflow 的 ticket approval shape；
- 与 04 Memory Knowledge 的 policy decision shape。

## 安全测试

- 未授权 approver 被拒绝；
- requester 不能审批自己的请求；
- policy author 不能发布自己的未 review policy；
- audit/API/log 不泄漏 sensitive raw input；
- override 必须满足 scope、expiry 和独立审批。
