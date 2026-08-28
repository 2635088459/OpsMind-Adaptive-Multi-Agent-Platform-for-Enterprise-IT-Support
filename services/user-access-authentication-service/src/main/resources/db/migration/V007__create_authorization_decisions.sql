-- SPEC-UA-002 (07-data-model §authorization_decisions;
-- 09-concurrency-and-idempotency: "(decisionKey, inputHash) dedup").
-- Append-only immutable facts — no version column.
CREATE TABLE identity.authorization_decisions (
    decision_id VARCHAR(64) PRIMARY KEY,
    decision_key VARCHAR(128) NOT NULL,
    input_hash VARCHAR(128) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    subject_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(64),
    action VARCHAR(128) NOT NULL,
    resource_type VARCHAR(128),
    resource_id VARCHAR(255),
    effect VARCHAR(16) NOT NULL,
    evaluated_roles JSONB NOT NULL DEFAULT '[]'::jsonb,
    evaluated_scopes JSONB NOT NULL DEFAULT '[]'::jsonb,
    ownership_satisfied BOOLEAN NOT NULL DEFAULT FALSE,
    assurance_level VARCHAR(64),
    reason_codes JSONB NOT NULL DEFAULT '[]'::jsonb,
    constraints JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    correlation_id VARCHAR(128) NOT NULL,

    CONSTRAINT uq_authorization_decisions_key_hash UNIQUE (decision_key, input_hash),
    CONSTRAINT ck_authorization_decisions_effect CHECK (effect IN ('ALLOW', 'DENY', 'REQUIRE_STEP_UP'))
);

CREATE INDEX ix_authorization_decisions_subject_created ON identity.authorization_decisions (subject_id, created_at);
CREATE INDEX ix_authorization_decisions_resource ON identity.authorization_decisions (resource_type, resource_id);
