# SPEC-TW-033 领域规则

- 本 SPEC 是 Phase 09 hardening，不新增 Ticket 主生命周期状态。
- 任何 queue-scoped actor 只能读取或操作其授权 Support Queue 范围内的 Ticket。
- policy 应在 mutation 前执行；读取场景应在敏感字段 materialize 前执行。
- fail-closed 的能力不得被 fallback、重试或 partial response 绕过。
- 任何 policy decision 都必须留下可审计 decision code。
