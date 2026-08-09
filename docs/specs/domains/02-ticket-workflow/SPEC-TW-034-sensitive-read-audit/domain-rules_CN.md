# SPEC-TW-034 领域规则

- 本 SPEC 是 Phase 09 hardening，不新增 Ticket 主生命周期状态。
- 敏感详情不得在 required audit 写入失败时返回给调用方。
- policy 应在 mutation 前执行；读取场景应在敏感字段 materialize 前执行。
- fail-closed 的能力不得被 fallback、重试或 partial response 绕过。
- 任何 policy decision 都必须留下可审计 decision code。
