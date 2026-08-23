# Acceptance Criteria — SPEC-PG-014

## 功能验收

- 能完成目标：实现 policy/approval/admin/audit 的 RBAC + ABAC 授权模型。
- 所有状态迁移符合 `11-security, 02-business-invariants` 中定义的规则。
- API、事件、persistence 或 worker 行为有明确 happy path、重复请求、失败路径。
- 不产生 Tool/Ticket/Workflow/Memory 的直接副作用。

## 治理验收

- decision 保存 policy version、input hash、reason codes 和 constraints。
- approval decision 校验 actor 权限、request linkage 和职责分离。
- denied、expired、cancelled、policy denied 保持不同语义。
- audit record 足以解释谁请求、谁审批、依据哪个 policy、为什么。

## 可靠性验收

- 重复请求或重复事件不会产生冲突 final decision。
- outbox 事件可重放且 event id 稳定。
- evaluator failure 和 degraded mode 行为可测试。
