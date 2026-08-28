-- SPEC-UA-031 (07-data-model §user_identities: "Email/display name may be
-- encrypted and erased by retention"). Anchors PII retention on the
-- already-existing deprovisioned_at column: only a DEPROVISIONED identity
-- past its retention window is ever eligible.
ALTER TABLE identity.user_identities ADD COLUMN pii_redacted_at TIMESTAMPTZ;

CREATE INDEX ix_user_identities_deprovisioned_pending_redaction
    ON identity.user_identities (deprovisioned_at)
    WHERE deprovisioned_at IS NOT NULL AND pii_redacted_at IS NULL;
