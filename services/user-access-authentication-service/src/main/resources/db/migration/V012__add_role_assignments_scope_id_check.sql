-- SPEC-UA-013 (11-security: "Controller, application use case, and
-- repository query all enforce tenant/scope"; ResourceScope's own javadoc:
-- "The richer tenant/queue scope model is SPEC-UA-013's job"). Defense in
-- depth alongside the domain-level ResourceScope compact-constructor
-- validation: SELF/TENANT never carry a scope_id (TENANT needs none at
-- all — tenant is already the row's own tenant_id column);
-- SUPPORT_QUEUE/RESOURCE always require a real, non-blank one.
ALTER TABLE identity.role_assignments
    ADD CONSTRAINT ck_role_assignments_scope_id_matches_type CHECK (
        (scope_type IN ('SELF', 'TENANT') AND scope_id IS NULL)
        OR (scope_type IN ('SUPPORT_QUEUE', 'RESOURCE') AND scope_id IS NOT NULL AND btrim(scope_id) <> '')
    );
