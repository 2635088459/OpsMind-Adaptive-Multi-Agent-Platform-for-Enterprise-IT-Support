-- SPEC-TW-034 Sensitive Read Audit: dedicated policy-decision ledger.
--
-- Adapted from the SPEC's reference migration
-- (V034__sensitive_read_audit_hardening.sql), mirroring SPEC-TW-033's own
-- V036 adaptation: the real next Flyway version in this schema is V037, and
-- ticket_id is VARCHAR rather than UUID because the SPEC's own API contract
-- and examples.http pass a display id ("TCK-1001"), not the internal
-- ticket.tickets UUID primary key. This ledger is separate from the
-- pre-existing ticket.audit_records SENSITIVE_READ rows written by Get
-- Ticket (SPEC-TW-002) and Ticket Timeline (SPEC-TW-006): those remain the
-- required, fail-closed business audit trail; this table is the
-- SPEC-TW-034 policy-decision trail (ALLOW/DENY/FAIL_CLOSED) for the
-- internal policy-evaluate endpoint and the hardened integration points.
CREATE TABLE ticket.sensitive_read_audit_decisions (
    id UUID PRIMARY KEY,
    ticket_id VARCHAR(64),
    actor_id VARCHAR(128) NOT NULL,
    actor_type VARCHAR(64) NOT NULL,
    operation VARCHAR(128) NOT NULL,
    decision VARCHAR(32) NOT NULL,
    decision_code VARCHAR(128) NOT NULL,
    correlation_id VARCHAR(128),
    trace_id VARCHAR(64),
    occurred_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT ck_sensitive_read_audit_decision CHECK (decision IN ('ALLOW', 'DENY', 'FAIL_CLOSED'))
);

CREATE INDEX ix_sensitive_read_audit_decisions_ticket ON ticket.sensitive_read_audit_decisions (ticket_id, occurred_at DESC);
CREATE INDEX ix_sensitive_read_audit_decisions_actor ON ticket.sensitive_read_audit_decisions (actor_id, occurred_at DESC);
