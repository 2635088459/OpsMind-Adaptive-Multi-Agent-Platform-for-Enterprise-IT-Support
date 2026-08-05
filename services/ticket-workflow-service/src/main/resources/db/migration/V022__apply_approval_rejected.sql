-- SPEC-TW-016 (Apply Approval Rejected): rejection metadata on the approval
-- request row opened by SPEC-TW-014 (V020).

ALTER TABLE ticket.ticket_approval_requests
    ADD COLUMN rejected_by VARCHAR(128),
    ADD COLUMN rejected_at TIMESTAMPTZ,
    ADD COLUMN rejection_reason VARCHAR(256),
    ADD COLUMN rejected_event_id VARCHAR(64);

ALTER TABLE ticket.ticket_approval_requests
    ADD CONSTRAINT ck_approval_request_rejected CHECK (
        request_status <> 'REJECTED' OR (
            rejected_at IS NOT NULL
            AND rejection_reason IS NOT NULL
            AND rejected_event_id IS NOT NULL
        )
    );
