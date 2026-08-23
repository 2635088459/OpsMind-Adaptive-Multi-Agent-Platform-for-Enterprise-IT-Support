CREATE TABLE governance.policy_versions (
    policy_version_id VARCHAR(64) PRIMARY KEY,
    policy_id VARCHAR(64) NOT NULL REFERENCES governance.policies (policy_id),
    version_number INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL,
    rules JSONB NOT NULL DEFAULT '[]'::jsonb,
    effective_from TIMESTAMPTZ,
    effective_to TIMESTAMPTZ,
    created_by VARCHAR(128) NOT NULL,
    reviewed_by VARCHAR(128),
    published_by VARCHAR(128),
    published_at TIMESTAMPTZ,

    CONSTRAINT uq_policy_versions_policy_version UNIQUE (policy_id, version_number),
    CONSTRAINT ck_policy_versions_status
        CHECK (status IN ('DRAFT', 'REVIEWING', 'REJECTED', 'PUBLISHED', 'SUPERSEDED', 'DEPRECATED', 'ARCHIVED', 'CANCELLED')),
    CONSTRAINT ck_policy_versions_rules_array CHECK (jsonb_typeof(rules) = 'array')
);
