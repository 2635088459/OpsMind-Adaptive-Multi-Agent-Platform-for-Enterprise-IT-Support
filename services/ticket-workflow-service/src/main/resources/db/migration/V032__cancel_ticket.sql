-- SPEC-TW-029 (Cancel Ticket): tickets.cancelled_at/cancel_reason_code
-- already exist since V002 (ck_tickets_cancelled_fields already requires
-- both NOT NULL whenever status = 'CANCELLED') — this codebase anticipated
-- Cancel from Phase 01 but never enforced a controlled vocabulary for
-- cancel_reason_code, unlike close_reason_code (V017) and
-- reopen_reason_code (V017). This migration only adds that missing CHECK
-- constraint, matching those two siblings, plus a reporting index
-- mirroring V016's ix_tickets_resolved_at / V017's ix_tickets_closed_at.

ALTER TABLE ticket.tickets
    ADD CONSTRAINT ck_tickets_cancel_reason_code CHECK (
        cancel_reason_code IS NULL OR cancel_reason_code IN (
            'REQUESTER_CANCELLED',
            'DUPLICATE_REQUEST',
            'CREATED_IN_ERROR',
            'NO_LONGER_NEEDED',
            'SUPPORT_CANCELLED'
        )
    );

CREATE INDEX IF NOT EXISTS ix_tickets_cancelled_at
    ON ticket.tickets (cancelled_at DESC)
    WHERE status = 'CANCELLED';
