# 02 Business Invariants

## 不变量

### INV-PG-001：06 不执行业务副作用

06 不能执行工具、修改 ticket、推进 workflow、写 active memory。它只产生治理事实。

### INV-PG-002：Policy Decision 必须可解释

每个 decision 必须保存 input hash、policy id/version、reason codes、constraints 和 evaluatedAt。

### INV-PG-003：审批不可伪造

Approval Decision 必须来自具备权限的 approver 或系统治理主体，且必须通过签名/session/audit 校验。

### INV-PG-004：职责分离必须验证

请求人、执行人、审批人不能违反 separation-of-duties policy。高风险 override 必须有独立审批人。

### INV-PG-005：审批只对匹配请求有效

approval granted/denied 只能作用于匹配 `approvalRequestId + sourceRequestId + requestHash` 的请求。

### INV-PG-006：Policy 版本不可静默改写历史

已发布版本不可变。修复规则必须发布新版本。

### INV-PG-007：Denied 与 Expired 必须可区分

下游需要知道是明确拒绝、过期、取消、还是 policy denied，不能合并成 generic denied。

### INV-PG-008：所有治理动作必须审计

policy draft/publish/deprecate、decision evaluate、approval grant/deny/expire/cancel、override 都必须写 audit。

## 跨域边界

- 05 依赖 06 决策，但 06 不调用 connector。
- 02/03 依赖 approval facts，但 06 不迁移它们的状态机。
- 04 依赖 retention/redaction policy，但 06 不保存知识内容。

