# SPEC-TW-025 — 持久化设计

真实 migration：`V031__resolve_ticket_with_verification.sql`。

新增或确认：

- Ticket `verification_evidence_id`
- resolution cycle `verification_id`
- resolution cycle `verification_evidence_id`

必须复用 Phase 03 resolution fields，并完成当前 resolution cycle。
