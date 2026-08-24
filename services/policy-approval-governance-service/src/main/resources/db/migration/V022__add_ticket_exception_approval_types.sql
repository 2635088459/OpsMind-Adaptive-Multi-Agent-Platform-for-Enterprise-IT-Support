-- SPEC-PG-023: three distinct sub-kinds of ticket-originated approval
-- (SLA exception, closure override, escalation exception —
-- 06-event-contracts §ticket.approval.required.v1), split out from the
-- generic TICKET_ACTION bucket so audit/metrics/RBAC can tell them apart.
-- Check constraints cannot be altered in place, so drop and recreate it.
ALTER TABLE governance.approval_requests DROP CONSTRAINT ck_approval_requests_approval_type;

ALTER TABLE governance.approval_requests ADD CONSTRAINT ck_approval_requests_approval_type
    CHECK (approval_type IN (
        'TOOL_EXECUTION', 'TICKET_ACTION', 'WORKFLOW_ACTION', 'POLICY_OVERRIDE',
        'TICKET_SLA_EXCEPTION', 'TICKET_CLOSURE_OVERRIDE', 'TICKET_ESCALATION_EXCEPTION',
        'GENERIC'
    ));
