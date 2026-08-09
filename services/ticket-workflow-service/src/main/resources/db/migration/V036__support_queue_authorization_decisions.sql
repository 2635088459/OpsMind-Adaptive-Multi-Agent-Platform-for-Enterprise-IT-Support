-- SPEC-TW-033 Support Queue Authorization: dedicated decision/audit ledger.
--
-- Adapted from the SPEC's reference migration
-- (V033__support_queue_authorization_hardening.sql): the real next Flyway
-- version in this schema is V036 (V001-V035 are already taken by Phase 01
-- to Phase 08), and ticket_id is VARCHAR rather than UUID because the SPEC's
-- own API contract and examples.http pass a display id ("TCK-1001"), not
-- the internal ticket.tickets UUID primary key.
CREATE TABLE ticket.support_queue_authorization_decisions (
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

    CONSTRAINT ck_support_queue_authorization_decision CHECK (decision IN ('ALLOW', 'DENY', 'FAIL_CLOSED'))
);

CREATE INDEX ix_support_queue_authorization_decisions_ticket ON ticket.support_queue_authorization_decisions (ticket_id, occurred_at DESC);
CREATE INDEX ix_support_queue_authorization_decisions_actor ON ticket.support_queue_authorization_decisions (actor_id, occurred_at DESC);
