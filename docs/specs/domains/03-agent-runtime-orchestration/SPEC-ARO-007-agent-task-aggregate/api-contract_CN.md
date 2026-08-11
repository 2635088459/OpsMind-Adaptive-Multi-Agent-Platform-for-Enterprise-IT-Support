# SPEC-ARO-007 — API 契约

目标：支撑 `Agent Task 聚合`。

- API 主要面向 internal service、worker 和 admin。
- Command 必须携带 idempotency key 或 workflow version。
- Query 只能返回 Runtime state，不声明 Ticket authoritative state。
- Admin API 必须记录 audit。
