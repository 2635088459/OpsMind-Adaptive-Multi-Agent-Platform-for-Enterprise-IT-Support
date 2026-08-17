# 02 Business Invariants

## 不变量

### INV-TG-001：Tool Gateway 是唯一工具执行入口

Agent、Runtime、Ticket Workflow、Memory Knowledge 都不能直接执行外部工具。它们只能提交请求、消费事件或读取结果。

### INV-TG-002：工具执行状态与 Ticket/Workflow 状态分离

Tool Request/Execution 的状态迁移不能直接修改 ticket 状态或 workflow 状态。Gateway 只能发布事实事件，由 02/03 领域自行决策。

### INV-TG-003：所有外部副作用必须幂等

任何可能改变外部系统状态的 connector 都必须有 `operationKey`。如果目标系统不支持 idempotency key，Gateway 必须在本域保存 reconciliation metadata，并把 connector 标记为 `EMULATED_IDEMPOTENCY`。

### INV-TG-004：凭据不可泄漏

凭据值不能进入：

- Agent prompt/context
- Runtime checkpoint
- Ticket comment
- Memory document
- Event payload
- Application log

凭据只能在 connector invocation 进程内短时存在。

### INV-TG-005：审批不可绕过

当 risk decision 要求审批时，Tool Request 必须进入 `WAITING_APPROVAL`，直到收到有效 `approval.granted.v1` 或 `approval.denied.v1`。

### INV-TG-006：审计记录不可缺失

以下动作必须产生 audit record：

- request accepted/rejected
- policy decision received
- approval requested/granted/denied
- credential binding resolved
- execution started/completed/failed/cancelled
- result redacted/published
- connector disabled/enabled

### INV-TG-007：原始输出默认不外发

`tool.completed.v1` 默认只携带 summary、structured redacted output、evidence refs 和 error metadata。raw output 只能通过受控 storage ref 查询。

### INV-TG-008：connector schema 必须版本化

每个 connector 的 input/output schema 都必须版本化。Tool Request 必须记录当时使用的 schema version，避免 connector 升级后历史请求无法解释。

### INV-TG-009：connector capability 不等于权限

Runtime 能看到 capability，不代表某个 Agent 一定能执行。实际执行必须综合 actor、tenant、ticket scope、risk、policy 和 credential binding。

### INV-TG-010：失败事实不能伪装成成功

connector timeout、policy denied、approval denied、non-retryable failure、partial side effect 都必须明确区分，不能统一映射成 generic failed。

## 跨域边界

- 03 可以创建 Tool Request，但不能选择凭据。
- 05 可以发布 `tool.completed.v1`，但不能完成 workflow。
- 06 可以批准/拒绝高风险动作，但不能直接执行工具。
- 04 可以保存工具 evidence 的脱敏版本，但不能保存 secret/raw output。

