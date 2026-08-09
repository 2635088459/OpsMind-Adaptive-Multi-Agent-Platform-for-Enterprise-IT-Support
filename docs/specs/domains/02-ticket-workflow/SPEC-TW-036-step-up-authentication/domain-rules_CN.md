# SPEC-TW-036 领域规则

- 本 SPEC 是 Phase 09 hardening，不新增 Ticket 主生命周期状态。
- 缺少有效 step-up proof 的高风险 command 必须在业务 mutation 前拒绝。
- policy 应在 mutation 前执行；读取场景应在敏感字段 materialize 前执行。
- fail-closed 的能力不得被 fallback、重试或 partial response 绕过。
- 任何 policy decision 都必须留下可审计 decision code。
