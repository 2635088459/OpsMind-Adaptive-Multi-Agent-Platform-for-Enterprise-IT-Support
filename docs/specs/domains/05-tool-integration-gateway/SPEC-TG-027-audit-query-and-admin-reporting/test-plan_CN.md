# Test Plan — SPEC-TG-027

## 单元测试

- domain state transition / rule validation；
- idempotency conflict；
- forbidden path 不会改变状态；
- error code 与 final status 映射。

## 集成测试

- PostgreSQL 持久化与唯一键；
- outbox 写入与重复发布；
- processed event 去重；
- worker/API/connector fake 的 happy path 与 failure path。

## 契约测试

- 与 03 Runtime 的 API/event shape；
- 与 06 Policy/Approval 的 approval/risk shape；
- 与 04 Memory Knowledge 的 redacted evidence shape；
- 与 02 Ticket Workflow 的 traceability，不直接迁移 Ticket state。

## 安全测试

- secret/raw output 不出现在日志、事件、响应、memory payload；
- 未授权 actor 被拒绝；
- 需要审批的 capability 未批准不能执行。
