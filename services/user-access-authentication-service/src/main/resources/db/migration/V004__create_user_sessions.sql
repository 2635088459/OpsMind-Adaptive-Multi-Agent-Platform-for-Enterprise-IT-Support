-- SPEC-UA-002 (07-data-model §user_sessions). Only hashes/metadata are
-- stored — never access, refresh, or ID tokens (02-business-invariants).
CREATE TABLE identity.user_sessions (
    user_session_id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    issuer VARCHAR(500) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    idp_session_id_hash VARCHAR(128),
    token_id_hash VARCHAR(128),
    client_id VARCHAR(128),
    acr VARCHAR(64) NOT NULL,
    amr JSONB NOT NULL DEFAULT '[]'::jsonb,
    auth_time TIMESTAMPTZ NOT NULL,
    device_id_hash VARCHAR(128),
    status VARCHAR(16) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_by VARCHAR(128),
    revoked_at TIMESTAMPTZ,
    revocation_reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT ck_user_sessions_status CHECK (status IN ('ACTIVE', 'EXPIRED', 'REVOKED', 'COMPROMISED', 'TERMINATED')),
    CONSTRAINT ck_user_sessions_amr_array CHECK (jsonb_typeof(amr) = 'array')
);

CREATE UNIQUE INDEX uq_user_sessions_token_id_hash ON identity.user_sessions (token_id_hash) WHERE token_id_hash IS NOT NULL;
CREATE INDEX ix_user_sessions_subject_status ON identity.user_sessions (tenant_id, subject, status);
CREATE INDEX ix_user_sessions_status_expires ON identity.user_sessions (status, expires_at);
