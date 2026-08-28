-- SPEC-UA-002/SPEC-UA-012 (07-data-model §role_assignments;
-- 03-state-machine §RoleAssignment; 09-concurrency-and-idempotency:
-- "Role assignment uses a partial active unique key plus transactional
-- validity-overlap checks").
CREATE TABLE identity.role_assignments (
    role_assignment_id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    user_identity_id VARCHAR(64) NOT NULL REFERENCES identity.user_identities (user_identity_id),
    role_code VARCHAR(32) NOT NULL,
    scope_type VARCHAR(16) NOT NULL,
    scope_id VARCHAR(255),
    permissions JSONB NOT NULL DEFAULT '[]'::jsonb,
    status VARCHAR(16) NOT NULL,
    valid_from TIMESTAMPTZ NOT NULL,
    valid_until TIMESTAMPTZ,
    granted_by VARCHAR(128) NOT NULL,
    grant_reason VARCHAR(500),
    revoked_by VARCHAR(128),
    revoked_at TIMESTAMPTZ,
    revocation_reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT ck_role_assignments_role_code CHECK (role_code IN ('EMPLOYEE', 'SUPPORT_AGENT', 'APPROVER', 'IT_ADMIN', 'PLATFORM_ADMIN', 'AUDITOR')),
    CONSTRAINT ck_role_assignments_scope_type CHECK (scope_type IN ('SELF', 'TENANT', 'SUPPORT_QUEUE', 'RESOURCE')),
    CONSTRAINT ck_role_assignments_status CHECK (status IN ('PENDING', 'ACTIVE', 'REVOKED', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT ck_role_assignments_permissions_array CHECK (jsonb_typeof(permissions) = 'array')
);

-- 03-state-machine: "Overlapping ACTIVE assignments for the same user,
-- role, and scope are prevented by constraint plus transactional
-- validation." COALESCE folds a NULL scope_id (tenant-wide) into a stable
-- value so Postgres treats two tenant-wide grants as a real duplicate
-- (NULL <> NULL under a plain unique index would not).
CREATE UNIQUE INDEX uq_role_assignments_active
    ON identity.role_assignments (user_identity_id, role_code, scope_type, COALESCE(scope_id, ''))
    WHERE status = 'ACTIVE';

CREATE INDEX ix_role_assignments_user_status ON identity.role_assignments (user_identity_id, status);
CREATE INDEX ix_role_assignments_status_valid_from ON identity.role_assignments (status, valid_from);
CREATE INDEX ix_role_assignments_status_valid_until ON identity.role_assignments (status, valid_until);
