-- SPEC-PG-030 (goal: "governance audit chain queries by
-- ticket/source/decision/approval/policy"): before this migration,
-- source_request_id was the only per-action business key on an audit
-- record, and it means something different depending on the action (the
-- decision key for DECISION_EVALUATED, the upstream request id for
-- approval actions) — there was no way to query "every audit record
-- touching this exact ticket/approval request/policy decision" directly.
ALTER TABLE governance.governance_audit_records
    ADD COLUMN ticket_id VARCHAR(64),
    ADD COLUMN approval_request_id VARCHAR(64),
    ADD COLUMN policy_decision_id VARCHAR(64);

CREATE INDEX ix_governance_audit_records_ticket ON governance.governance_audit_records (ticket_id);
CREATE INDEX ix_governance_audit_records_approval_request ON governance.governance_audit_records (approval_request_id);
CREATE INDEX ix_governance_audit_records_policy_decision ON governance.governance_audit_records (policy_decision_id);
CREATE INDEX ix_governance_audit_records_source_request ON governance.governance_audit_records (source_request_id);
CREATE INDEX ix_governance_audit_records_policy ON governance.governance_audit_records (policy_id);
