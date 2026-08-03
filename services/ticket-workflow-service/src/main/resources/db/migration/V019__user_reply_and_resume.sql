-- SPEC-TW-013 (User Reply and Resume): completes Phase 04's waiting-for-user
-- loop. Renumbered from the spec folder's reference "V013" to the next real
-- slot in this service's Flyway sequence (V001-V018 already exist).
--
-- The reference migration's ck_user_input_answered redefinition is already
-- exactly what V018 created (SPEC-TW-012), so it is a no-op here — kept for
-- parity with the reference file. Only the answered-message index is new.

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
