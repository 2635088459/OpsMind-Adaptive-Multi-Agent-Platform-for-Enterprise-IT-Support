-- SPEC-PG-009: back-reference from an ApprovalRequest to the PolicyDecision
-- it originated from, if any (01-domain-model §Aggregate Boundary: "may be
-- linked, but are not strictly one-to-one"; 03-state-machine's
-- APPROVAL_REQUIRED -> APPROVAL_LINKED transition). Nullable: an approval
-- request may exist without a prior policy evaluation. A real FK is safe
-- (not a cross-domain reference like ticket_id/workflow_instance_id) since
-- policy_decisions lives in this same governance schema and its rows are
-- never deleted.
ALTER TABLE governance.approval_requests
    ADD COLUMN policy_decision_id VARCHAR(64)
        REFERENCES governance.policy_decisions (policy_decision_id);

CREATE INDEX ix_approval_requests_policy_decision ON governance.approval_requests (policy_decision_id);
