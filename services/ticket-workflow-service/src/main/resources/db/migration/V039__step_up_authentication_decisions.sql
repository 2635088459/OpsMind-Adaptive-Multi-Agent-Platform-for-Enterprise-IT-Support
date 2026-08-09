-- SPEC-TW-036 Step-up Authentication: dedicated policy-decision ledger.
--
-- Adapted from the SPEC's reference migration
-- (V036__step_up_authentication_hardening.sql), mirroring SPEC-TW-033/034/035's
-- own V036/V037/V038 adaptations: the real next Flyway version in this
-- schema is V039, and ticket_id is VARCHAR rather than UUID because the
-- SPEC's own API contract and examples.http pass a display id
-- ("TCK-1001"), not the internal ticket.tickets UUID primary key. Never
-- stores authentication material — only the low-cardinality decision_code
-- (domain-rules mirrors persistence_EN: "Step-up proof stores only proof
-- id, method, verifiedAt, and expiresAt, not authentication material", and
-- even those four fields are not persisted here, only the resulting
-- decision).
CREATE TABLE ticket.step_up_authentication_decisions (
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

    CONSTRAINT ck_step_up_authentication_decision CHECK (decision IN ('ALLOW', 'DENY', 'FAIL_CLOSED'))
);

CREATE INDEX ix_step_up_authentication_decisions_ticket ON ticket.step_up_authentication_decisions (ticket_id, occurred_at DESC);
CREATE INDEX ix_step_up_authentication_decisions_actor ON ticket.step_up_authentication_decisions (actor_id, occurred_at DESC);
