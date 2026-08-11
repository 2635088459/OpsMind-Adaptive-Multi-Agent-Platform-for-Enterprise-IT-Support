# SPEC-ARO-034 — 领域规则

目标：支撑 `Runtime 可观测性`。

- Runtime state 与 Ticket state 必须分离。
- Agent 不能直接调用 Tool。
- 状态迁移必须校验当前 state、version 和幂等键。
- 失败路径必须保留可审计原因。
