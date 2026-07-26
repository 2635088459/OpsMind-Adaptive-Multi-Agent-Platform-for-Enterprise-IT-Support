ALTER TABLE ticket.audit_records
    ADD COLUMN view_type VARCHAR(32),
    ADD COLUMN fields_policy_version VARCHAR(32);
