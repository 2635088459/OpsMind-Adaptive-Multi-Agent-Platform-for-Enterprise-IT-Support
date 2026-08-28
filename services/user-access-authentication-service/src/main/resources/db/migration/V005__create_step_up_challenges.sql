-- SPEC-UA-002/SPEC-UA-017 (07-data-model §step_up_challenges;
-- 02-business-invariants: "Step-up evidence ... is single use";
-- 09-concurrency-and-idempotency: "Step-up consumption uses atomic
-- conditional update and unique proofIdHash").
CREATE TABLE identity.step_up_challenges (
    step_up_challenge_id VARCHAR(64) PRIMARY KEY,
    challenge_key VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    issuer VARCHAR(500) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    user_session_id VARCHAR(64) NOT NULL REFERENCES identity.user_sessions (user_session_id),
    action VARCHAR(128) NOT NULL,
    resource_type VARCHAR(128),
    resource_id VARCHAR(255),
    required_assurance_level VARCHAR(64),
    required_methods JSONB NOT NULL DEFAULT '[]'::jsonb,
    nonce_hash VARCHAR(128),
    status VARCHAR(16) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    verified_at TIMESTAMPTZ,
    proof_id_hash VARCHAR(128),
    consumed_at TIMESTAMPTZ,
    correlation_id VARCHAR(128) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT uq_step_up_challenges_key UNIQUE (challenge_key),
    CONSTRAINT ck_step_up_challenges_status CHECK (status IN ('REQUESTED', 'PENDING', 'VERIFIED', 'CONSUMED', 'FAILED', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT ck_step_up_challenges_required_methods_array CHECK (jsonb_typeof(required_methods) = 'array')
);

-- Single-use: at most one challenge may ever have consumed a given proof.
CREATE UNIQUE INDEX uq_step_up_challenges_proof_id_hash ON identity.step_up_challenges (proof_id_hash) WHERE proof_id_hash IS NOT NULL;
CREATE INDEX ix_step_up_challenges_status_expires ON identity.step_up_challenges (status, expires_at);
CREATE INDEX ix_step_up_challenges_session ON identity.step_up_challenges (user_session_id);
