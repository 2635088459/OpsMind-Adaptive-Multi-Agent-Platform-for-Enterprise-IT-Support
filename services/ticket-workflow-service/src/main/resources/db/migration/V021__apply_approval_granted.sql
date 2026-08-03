-- SPEC-TW-015 (Apply Approval Granted): grant metadata on the approval
-- request row opened by SPEC-TW-014 (V020).

ALTER TABLE ticket.ticket_approval_requests
    ADD COLUMN approved_by VARCHAR(128),
    ADD COLUMN approved_at TIMESTAMPTZ,
    ADD COLUMN authorization_reference VARCHAR(128),
    ADD COLUMN granted_event_id VARCHAR(64);

ALTER TABLE ticket.ticket_approval_requests
    ADD CONSTRAINT ck_approval_request_granted CHECK (
        request_status <> 'GRANTED' OR (
            approved_by IS NOT NULL
            AND approved_at IS NOT NULL
            AND authorization_reference IS NOT NULL
            AND granted_event_id IS NOT NULL
        )
    );
