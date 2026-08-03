-- Reference migration for SPEC-TW-013.
-- Real service migration should be named V019__user_reply_and_resume.sql.

ALTER TABLE ticket.ticket_user_input_requests
    DROP CONSTRAINT IF EXISTS ck_user_input_answered;

ALTER TABLE ticket.ticket_user_input_requests
    ADD CONSTRAINT ck_user_input_answered CHECK (
        request_status <> 'ANSWERED'
        OR (answered_message_id IS NOT NULL AND answered_at IS NOT NULL)
    );

CREATE INDEX IF NOT EXISTS ix_user_input_answered_message
    ON ticket.ticket_user_input_requests (answered_message_id)
    WHERE answered_message_id IS NOT NULL;
