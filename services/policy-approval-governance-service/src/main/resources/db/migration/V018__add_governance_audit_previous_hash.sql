-- SPEC-PG-017 (11-security §Tamper-Resistant Audit: "Audit record should
-- include hash chain or append-only marker"). Nullable: null only for the
-- very first record this service ever appends.
ALTER TABLE governance.governance_audit_records
    ADD COLUMN previous_hash VARCHAR(64);
