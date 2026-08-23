# Policy Approval Governance LLD

## 范围

本目录定义 `06-policy-approval-governance` 的低层设计。该域负责平台级策略决策、风险分级、审批生命周期、治理审计、职责分离和变更控制。

Policy Approval Governance 不执行 Tool，不推进 Ticket state，不推进 Agent Workflow state，也不保存 Memory。它只回答：某个请求是否允许、风险等级是什么、是否需要审批、谁能审批、审批是否有效、治理证据如何审计。

## 核心回答

- Policy Decision 是对某个 action/request 的治理判断，包括 allow/deny、risk level、approval requirement、constraints、reason codes 和 policy version。
- Approval Request 是一个需要人工或治理主体决策的请求，通常由 Tool Gateway、Ticket Workflow 或 Runtime 发起。
- Approval Decision 是审批人或审批策略对 Approval Request 的最终决定，包括 granted/denied/expired/cancelled。
- Governance Audit 是不可缺失的审计链，记录策略版本、请求来源、审批人、职责分离、决策原因和输出约束。
- 06 不执行工具。05 Tool Gateway 才执行工具；06 只返回 risk/approval/decision。
- 06 不直接修改 Ticket/Workflow state。它发布事实事件，由 02/03/05 自行消费。
- 高风险工具、敏感数据访问、越权恢复、admin repair、policy override 都必须经过 06。
- Policy 与 Approval 必须幂等：重复评估、重复提交审批、重复 decision event 不能产生冲突结果。
- Policy version 必须进入 decision snapshot，确保历史执行可解释。

## 为什么需要独立治理域

如果每个域各自实现审批和策略，会导致：

- 同一个风险动作在不同入口得到不同结果；
- 审批人职责分离无法统一验证；
- 工具、ticket、memory、runtime 的审计链割裂；
- policy 变更无法解释历史决策；
- 高风险 override 无法统一管控。

因此 06 是平台治理的 owner：它集中管理规则、审批、职责分离、policy version、decision reason 和 audit trail。

## 与其他域的关系

- `02-ticket-workflow`：请求 ticket escalation、closure override、SLA exception 等治理判断；06 不改 Ticket state。
- `03-agent-runtime-orchestration`：请求 workflow pause/resume override、agent permission、automation risk 判断；06 不改 Workflow state。
- `04-memory-knowledge`：请求 retention、redaction、sensitive retrieval、memory publication policy；06 不写 Memory。
- `05-tool-integration-gateway`：请求 tool risk decision 和 approval；06 不执行 Tool。
- `07-evaluation-improvement`：消费治理结果评估自动化质量、审批摩擦和 policy effectiveness。
- `08-observability-platform`：汇聚 policy decision、approval latency、governance audit 和 compliance signals。

## 14 个 LLD 切面

1. `01-domain-model`：Policy、Rule、Policy Decision、Approval Request、Approval Decision、Governance Audit。
2. `02-business-invariants`：职责分离、policy version、审批不可伪造、状态分离。
3. `03-state-machine`：Policy lifecycle、Approval Request、Approval Decision、Override 状态机。
4. `04-use-cases`：风险评估、审批创建、审批决策、过期、撤销、override、policy 发布。
5. `05-api-contracts`：Decision API、Approval API、Admin Policy API、Audit API。
6. `06-event-contracts`：消费治理请求事件，发布 approval/policy/governance 事件。
7. `07-data-model`：PostgreSQL 表、版本、唯一键、审计和保留。
8. `08-transaction-and-outbox`：decision/approval/audit/outbox 事务边界。
9. `09-concurrency-and-idempotency`：重复决策、重复审批、并发审批、policy version race。
10. `10-failure-handling`：policy evaluation failure、approval timeout、poison decision、degraded policy。
11. `11-security`：RBAC/ABAC、审批权限、职责分离、override guard、审计不可篡改。
12. `12-observability`：decision latency、approval SLA、deny rate、override rate、audit trace。
13. `13-package-and-class-design`：service、ports/adapters、rule evaluator、approval service。
14. `14-testing-strategy`：unit、integration、contract、security、recovery、compliance tests。

## 冻结原则

- 06 只能输出 governance facts，不能执行业务副作用。
- 每个 decision 必须保存 policy version、input hash、reason code 和 constraints。
- 每个 approval decision 必须校验 approver 权限与职责分离。
- 每个下游域必须幂等消费 approval/policy events。
- Policy 变更只能影响未来或未 final 的请求，不能静默改写历史决策。
