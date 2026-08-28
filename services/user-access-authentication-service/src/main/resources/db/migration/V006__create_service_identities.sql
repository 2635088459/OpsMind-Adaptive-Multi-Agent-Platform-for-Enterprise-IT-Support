-- SPEC-UA-002 (07-data-model §service_identities).
CREATE TABLE identity.service_identities (
    service_identity_id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    issuer VARCHAR(500) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    client_id VARCHAR(128) NOT NULL,
    service_name VARCHAR(255) NOT NULL,
    allowed_audiences JSONB NOT NULL DEFAULT '[]'::jsonb,
    allowed_scopes JSONB NOT NULL DEFAULT '[]'::jsonb,
    status VARCHAR(16) NOT NULL,
    valid_from TIMESTAMPTZ,
    valid_until TIMESTAMPTZ,
    last_seen_at TIMESTAMPTZ,
    disabled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT uq_service_identities_subject UNIQUE (tenant_id, issuer, subject),
    CONSTRAINT uq_service_identities_client UNIQUE (tenant_id, client_id),
    CONSTRAINT ck_service_identities_status CHECK (status IN ('ACTIVE', 'DISABLED', 'RETIRED')),
    CONSTRAINT ck_service_identities_audiences_array CHECK (jsonb_typeof(allowed_audiences) = 'array'),
    CONSTRAINT ck_service_identities_scopes_array CHECK (jsonb_typeof(allowed_scopes) = 'array')
);

CREATE INDEX ix_service_identities_status_valid_until ON identity.service_identities (status, valid_until);
