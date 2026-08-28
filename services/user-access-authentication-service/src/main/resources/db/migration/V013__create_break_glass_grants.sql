-- SPEC-UA-019 (Break Glass And Account Recovery — 04-use-cases §Break-glass:
-- "Strong authentication + dual/06 approval + bounded time/scope"; 11-security:
-- "requires strong authentication, domain-06 approval/dual control, bounded
-- scope/time, and non-disableable audit"). No 07-data-model table was ever
-- named for this aggregate — the concept exists only in this domain's own
-- use-case/security prose, not its data model — so this schema is this
-- spec's own real, from-scratch design, following the exact same
-- conventions (client-assigned id, optimistic version, tenant+subject
-- keying) every other table in this schema already uses.
CREATE TABLE identity.break_glass_grants (
    break_glass_grant_id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    issuer VARCHAR(500) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    scope_type VARCHAR(16) NOT NULL,
    scope_id VARCHAR(255),
    approval_reference VARCHAR(255) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    granted_by VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    granted_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_by VARCHAR(128),
    revoked_at TIMESTAMPTZ,
    revocation_reason VARCHAR(500),
    correlation_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT ck_break_glass_grants_scope_type CHECK (scope_type IN ('SELF', 'TENANT', 'SUPPORT_QUEUE', 'RESOURCE')),
    -- SPEC-UA-013's own rule, reused verbatim: SELF/TENANT never carry a scope_id; SUPPORT_QUEUE/RESOURCE always require one.
    CONSTRAINT ck_break_glass_grants_scope_id_matches_type CHECK (
        (scope_type IN ('SELF', 'TENANT') AND scope_id IS NULL)
        OR (scope_type IN ('SUPPORT_QUEUE', 'RESOURCE') AND scope_id IS NOT NULL AND btrim(scope_id) <> '')
    ),
    CONSTRAINT ck_break_glass_grants_status CHECK (status IN ('ACTIVE', 'EXPIRED', 'REVOKED')),
    -- Break-glass access can never be unbounded (11-security: "bounded scope/time") — no NULL expires_at, unlike every other aggregate's optional validUntil.
    CONSTRAINT ck_break_glass_grants_bounded_expiry CHECK (expires_at > granted_at)
);

CREATE INDEX ix_break_glass_grants_status_expires_at ON identity.break_glass_grants (status, expires_at);
CREATE INDEX ix_break_glass_grants_tenant_subject ON identity.break_glass_grants (tenant_id, issuer, subject);
