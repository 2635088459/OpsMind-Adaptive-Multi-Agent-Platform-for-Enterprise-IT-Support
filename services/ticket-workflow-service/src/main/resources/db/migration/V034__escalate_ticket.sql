-- SPEC-TW-031 (Escalate Ticket): ESCALATED has been a valid
-- ticket.tickets.status value since V002 (Phase06/07 already transition
-- EXECUTING/VERIFYING tickets into it on tool-result-unknown/unsafe-
-- verification-failure paths), but no column has ever recorded *why* or
-- *when* a ticket entered that status, unlike close_reason_code (V002),
-- cancel_reason_code (V002/V032), and reopen_reason_code (V017). This
-- migration adds that missing classification, mirroring V032's Cancel
-- pattern exactly: a reason-code column with its own CHECK constraint
-- (nullable — Phase06/07's system-driven escalations, e.g.
-- ck_tickets_status transitions from EXECUTING/VERIFYING, do not go
-- through this command and never populate it), escalated_at/escalated_by
-- for the audit trail, and a partial reporting index.
--
-- The reference migration's `ticket_phase8_escalate_ticket_audit` table is
-- rejected in favor of the existing generic ticket_status_history/
-- audit_records/outbox_events tables, per this service's established
-- Phase08 convention (SPEC-TW-026/027/028/029/030 all made the same call).
--
-- active_workflow_id is deliberately NOT nulled by this migration's own
-- constraints (domain-rules: "Escalation freezes automated progression" —
-- enforced by the application/persistence layer clearing it on write, the
-- same way Cancel's persistence adapter does; no CHECK constraint forces
-- it, since Phase06/07's system-driven escalation paths may still have a
-- legitimate in-flight workflow to hand off during Resume).

ALTER TABLE ticket.tickets
    ADD COLUMN escalated_at TIMESTAMPTZ,
    ADD COLUMN escalated_by VARCHAR(128),
    ADD COLUMN escalation_reason_code VARCHAR(32);

ALTER TABLE ticket.tickets
    ADD CONSTRAINT ck_tickets_escalation_reason_code CHECK (
        escalation_reason_code IS NULL OR escalation_reason_code IN (
            'USER_IMPACT',
            'SLA_RISK',
            'SECURITY_RISK',
            'REPEATED_FAILURE',
            'POLICY_REQUIRED',
            'SUPPORT_REQUEST'
        )
    );

-- Command-driven escalations (this SPEC) always populate escalated_at/
-- escalated_by together; Phase06/07's system-driven paths (SPEC-TW-020/021)
-- leave both NULL, so this constraint only requires the pair be consistent
-- with each other, not with status = 'ESCALATED' itself.
ALTER TABLE ticket.tickets
    ADD CONSTRAINT ck_tickets_escalated_fields CHECK (
        (escalated_at IS NULL) = (escalated_by IS NULL)
    );

CREATE INDEX IF NOT EXISTS ix_tickets_escalated_at
    ON ticket.tickets (escalated_at DESC)
    WHERE status = 'ESCALATED';
