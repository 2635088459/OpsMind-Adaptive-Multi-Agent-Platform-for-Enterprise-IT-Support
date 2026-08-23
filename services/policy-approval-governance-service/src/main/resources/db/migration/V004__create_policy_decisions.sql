CREATE TABLE governance.policy_decisions (
    policy_decision_id VARCHAR(64) PRIMARY KEY,
    decision_key VARCHAR(200) NOT NULL,
    input_hash VARCHAR(128) NOT NULL,
    subject_type VARCHAR(64) NOT NULL,
    subject_id VARCHAR(128) NOT NULL,
    action_type VARCHAR(64) NOT NULL,
    resource_type VARCHAR(64),
    resource_id VARCHAR(128),
    tenant_id VARCHAR(64),
    source_domain VARCHAR(64) NOT NULL,
    source_request_id VARCHAR(128) NOT NULL,
    ticket_id VARCHAR(64),
    workflow_instance_id VARCHAR(64),
    effect VARCHAR(32) NOT NULL,
    risk_level VARCHAR(16) NOT NULL,
    approval_required BOOLEAN NOT NULL DEFAULT FALSE,
    constraints JSONB NOT NULL DEFAULT '[]'::jsonb,
    reason_codes JSONB NOT NULL DEFAULT '[]'::jsonb,
    policy_id VARCHAR(64) NOT NULL,
    policy_version VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,

    CONSTRAINT uq_policy_decisions_key_hash UNIQUE (decision_key, input_hash),
    CONSTRAINT ck_policy_decisions_effect CHECK (effect IN ('ALLOW', 'DENY', 'REQUIRE_APPROVAL', 'ALLOW_WITH_CONSTRAINTS')),
    CONSTRAINT ck_policy_decisions_risk_level CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_policy_decisions_constraints_array CHECK (jsonb_typeof(constraints) = 'array'),
    CONSTRAINT ck_policy_decisions_reason_codes_array CHECK (jsonb_typeof(reason_codes) = 'array')
);

CREATE INDEX ix_policy_decisions_source ON governance.policy_decisions (source_domain, source_request_id);
