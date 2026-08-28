-- SPEC-UA-002 (07-data-model §user_identities; 02-business-invariants:
-- "(tenantId, issuer, subject) is the stable user identity").
CREATE TABLE identity.user_identities (
    user_identity_id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    issuer VARCHAR(500) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    username VARCHAR(255),
    display_name VARCHAR(255),
    email VARCHAR(320),
    identity_type VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    profile_version BIGINT NOT NULL DEFAULT 0,
    linked_at TIMESTAMPTZ NOT NULL,
    last_synced_at TIMESTAMPTZ,
    disabled_at TIMESTAMPTZ,
    deprovisioned_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT uq_user_identities_subject UNIQUE (tenant_id, issuer, subject),
    CONSTRAINT ck_user_identities_identity_type CHECK (identity_type IN ('HUMAN', 'WORKLOAD')),
    CONSTRAINT ck_user_identities_status CHECK (status IN ('ACTIVE', 'DISABLED', 'DEPROVISIONED'))
);

CREATE INDEX ix_user_identities_tenant_status ON identity.user_identities (tenant_id, status);
