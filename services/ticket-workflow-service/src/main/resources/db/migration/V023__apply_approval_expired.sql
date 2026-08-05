-- SPEC-TW-017 (Apply Approval Expired): expiration metadata on the approval
-- request row opened by SPEC-TW-014 (V020).

ALTER TABLE ticket.ticket_approval_requests
    ADD COLUMN expired_at TIMESTAMPTZ,
    ADD COLUMN expired_event_id VARCHAR(64),
    ADD COLUMN expiration_reason VARCHAR(128);

ALTER TABLE ticket.ticket_approval_requests
    ADD CONSTRAINT ck_approval_request_expired CHECK (
        request_status <> 'EXPIRED' OR (
            expired_at IS NOT NULL
            AND expired_event_id IS NOT NULL
            AND expiration_reason IS NOT NULL
        )
    );
