-- SPEC-TW-032 (Resume Escalated Ticket): mirrors V034's Escalate pattern —
-- a reason-code column with its own CHECK constraint plus resumed_at/
-- resumed_by for the audit trail, and a partial reporting index. Resume
-- deliberately does NOT touch escalated_at/escalated_by/escalation_reason_code
-- (domain-rules: "cannot discard the escalation resolution notes") — this
-- migration only adds the *new* columns Resume itself needs.
--
-- The reference migration's `ticket_phase8_resume_escalated_ticket_audit`
-- table is rejected in favor of the existing generic
-- ticket_status_history/audit_records/outbox_events tables, per this
-- service's established Phase08 convention.

ALTER TABLE ticket.tickets
    ADD COLUMN escalation_resumed_at TIMESTAMPTZ,
    ADD COLUMN escalation_resumed_by VARCHAR(128),
    ADD COLUMN escalation_resume_reason_code VARCHAR(32);

ALTER TABLE ticket.tickets
    ADD CONSTRAINT ck_tickets_escalation_resume_reason_code CHECK (
        escalation_resume_reason_code IS NULL OR escalation_resume_reason_code IN (
            'ROOT_CAUSE_RESOLVED',
            'MITIGATION_APPLIED',
            'RISK_ACCEPTED',
            'ESCALATION_NOT_REQUIRED',
            'OWNER_REASSIGNED'
        )
    );

ALTER TABLE ticket.tickets
    ADD CONSTRAINT ck_tickets_escalation_resumed_fields CHECK (
        (escalation_resumed_at IS NULL) = (escalation_resumed_by IS NULL)
    );

CREATE INDEX IF NOT EXISTS ix_tickets_escalation_resumed_at
    ON ticket.tickets (escalation_resumed_at DESC)
    WHERE escalation_resumed_at IS NOT NULL;
