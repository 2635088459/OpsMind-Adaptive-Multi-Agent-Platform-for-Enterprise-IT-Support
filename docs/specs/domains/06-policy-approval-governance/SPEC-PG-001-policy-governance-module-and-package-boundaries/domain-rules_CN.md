# Domain Rules — SPEC-PG-001

## 必须遵守

- 06 只能输出治理事实；decision 必须绑定 policy version/input hash/reason codes/constraints；approval final decision 必须幂等且唯一；所有治理状态迁移必须同事务写 audit/outbox。
- Policy published version 不可变；
- Approval final state 不可逆；
- Approval 只对匹配 approvalRequestId/sourceRequestId/requestHash 的请求有效。

## 禁止

- Tool 直接执行；Ticket/Workflow state 直接修改；Memory 内容写入；伪造审批；绕过职责分离；跨 domain 分布式事务。
- 默认 allow policy evaluator failure；
- 把 expired/cancelled/denied 合并为一个 generic denied；
- 静默修改历史 decision 或 audit record。
