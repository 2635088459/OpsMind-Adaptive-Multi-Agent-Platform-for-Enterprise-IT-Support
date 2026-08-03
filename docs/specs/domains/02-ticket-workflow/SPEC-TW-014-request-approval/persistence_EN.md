# SPEC-TW-014 — Persistence Design

Real migration: `V020__request_approval.sql`.

Add `ticket.ticket_approval_requests`:

- `approval_request_id UUID`
- `ticket_id UUID`
- `approval_id VARCHAR(128)`
- `workflow_id VARCHAR(128)`
- `action_id VARCHAR(128)`
- `action_type VARCHAR(64)`
- `request_status VARCHAR(24)`
- `risk_level VARCHAR(24)`
- `risk_context JSONB`
- `requested_by_type`
- `requested_by_id`
- `requested_at`
- `expires_at`

Unique constraint: one `OPEN` approval request per ticket.
