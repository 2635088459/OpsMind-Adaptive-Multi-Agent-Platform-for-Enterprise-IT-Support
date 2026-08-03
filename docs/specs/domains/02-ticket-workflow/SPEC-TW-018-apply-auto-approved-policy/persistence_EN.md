# SPEC-TW-018 — Persistence Design

Real migration: `V024__apply_auto_approved_policy.sql`.

Add or confirm:

- `policy_decision_id`
- `policy_id`
- `policy_version`
- `auto_approved_at`
- `auto_approval_event_id`
- `authorization_reference`

May reuse `ticket_approval_requests` with status `AUTO_APPROVED`.
