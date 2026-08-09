-- SPEC-TW-035 Secret Detection: dedicated policy-decision ledger.
--
-- Adapted from the SPEC's reference migration
-- (V035__secret_detection_hardening.sql), mirroring SPEC-TW-033/034's own
-- V036/V037 adaptations: the real next Flyway version in this schema is
-- V038, and ticket_id is VARCHAR rather than UUID because the SPEC's own
-- API contract and examples.http pass a display id ("TCK-1001"), not the
-- internal ticket.tickets UUID primary key. Never stores the evaluated
-- free text or the matched secret pattern itself — only the low-cardinality
-- decision_code (domain-rules: "never persisted raw").
CREATE TABLE ticket.secret_detection_decisions (
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

    CONSTRAINT ck_secret_detection_decision CHECK (decision IN ('ALLOW', 'DENY', 'FAIL_CLOSED'))
);

CREATE INDEX ix_secret_detection_decisions_ticket ON ticket.secret_detection_decisions (ticket_id, occurred_at DESC);
CREATE INDEX ix_secret_detection_decisions_actor ON ticket.secret_detection_decisions (actor_id, occurred_at DESC);
