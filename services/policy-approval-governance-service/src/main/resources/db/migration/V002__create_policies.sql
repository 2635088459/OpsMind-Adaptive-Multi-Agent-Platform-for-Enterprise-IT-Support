CREATE TABLE governance.policies (
    policy_id VARCHAR(64) PRIMARY KEY,
    policy_name VARCHAR(200) NOT NULL,
    scope VARCHAR(100) NOT NULL,
    current_published_version INTEGER,
    status VARCHAR(16) NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT ck_policies_status CHECK (status IN ('ACTIVE', 'RETIRED'))
);
