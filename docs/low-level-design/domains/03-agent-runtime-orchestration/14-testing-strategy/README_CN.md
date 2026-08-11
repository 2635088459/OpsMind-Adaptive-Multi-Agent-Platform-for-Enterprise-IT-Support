# 14 Testing Strategy

## 单元测试

覆盖：

- Workflow state transition。
- Agent Task state transition。
- checkpoint payload schema/version。
- pause/resume 幂等规则。
- task dependency graph 解锁。
- Tool Gateway 禁止直连规则。

## 应用服务测试

覆盖：

- consume `ticket.created` 创建 Workflow Instance。
- consume `approval.granted` 恢复等待审批的 Workflow。
- consume `tool.completed` 恢复等待工具结果的 Workflow。
- consume `verification.completed` 完成或创建 remediation。
- pause 写 checkpoint、递增 generation、发布 outbox。
- resume 恢复 task 并发布 outbox。

## 数据库集成测试

覆盖：

- unique active workflow。
- processed event 去重。
- command idempotency request hash conflict。
- task claim 并发。
- outbox publisher retry。
- checkpoint latest 查询。

## 契约测试

对消费事件验证：

- `ticket.created.v1`
- `approval.granted.v1`
- `tool.completed.v1`
- `verification.completed.v1`

对发布事件验证：

- `workflow.started.v1`
- `workflow.paused.v1`
- `workflow.resumed.v1`
- `agent.task.completed.v1`
- `workflow.completed.v1`
- `workflow.failed.v1`

## 崩溃恢复测试

必须模拟：

- 事务提交前崩溃。
- outbox 发布前崩溃。
- task worker claim 后崩溃。
- Tool Request 创建后 Runtime 崩溃。
- pause 期间 worker 返回旧 generation result。
- duplicate external callback。

## 安全测试

覆盖：

- Agent Worker 无 Tool client。
- Tool Request 必须经过 Tool Gateway。
- checkpoint 不包含 secret。
- admin API 需要 admin 权限。
- log redaction。

## 验收标准

一个 spec 实现完成时，至少满足：

- 所有核心 domain transition 单测通过。
- 事件消费幂等测试通过。
- outbox 写入测试通过。
- pause/resume 重复请求测试通过。
- Tool Gateway 边界测试通过。
- 崩溃恢复路径至少有一个集成或组件测试覆盖。
