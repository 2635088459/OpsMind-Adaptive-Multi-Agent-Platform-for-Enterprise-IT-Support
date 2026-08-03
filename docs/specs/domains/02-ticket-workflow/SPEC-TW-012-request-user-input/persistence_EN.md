# SPEC-TW-012 — Persistence Design

Real service migration should be named:

```text
services/ticket-workflow-service/src/main/resources/db/migration/V018__request_user_input.sql
```

Spec reference file: `V012__request_user_input.sql`.

## Table: ticket_user_input_requests

Fields:

- `request_id UUID PRIMARY KEY`
- `ticket_id UUID NOT NULL`
- `request_status VARCHAR(24) NOT NULL`
- `prompt TEXT NOT NULL`
- `requested_fields JSONB`
- `requested_by_type VARCHAR(32) NOT NULL`
- `requested_by_id VARCHAR(128) NOT NULL`
- `requested_at TIMESTAMPTZ NOT NULL`
- `resume_status VARCHAR(32) NOT NULL`
- `answered_message_id UUID`
- `answered_at TIMESTAMPTZ`
- `expires_at TIMESTAMPTZ`
- `correlation_id VARCHAR(128)`

Uniqueness:

```sql
CREATE UNIQUE INDEX uq_ticket_one_open_user_input_request
    ON ticket.ticket_user_input_requests (ticket_id)
    WHERE request_status = 'OPEN';
```

Ticket update must guard on `status = 'IN_PROGRESS'` and expected version.
