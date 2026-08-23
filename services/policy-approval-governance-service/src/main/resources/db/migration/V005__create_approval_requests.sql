CREATE TABLE governance.approval_requests (
    approval_request_id VARCHAR(64) PRIMARY KEY,
    request_key VARCHAR(200) NOT NULL,
    request_hash VARCHAR(128) NOT NULL,
    source_domain VARCHAR(64) NOT NULL,
    source_request_id VARCHAR(128) NOT NULL,
    ticket_id VARCHAR(64),
    workflow_instance_id VARCHAR(64),
    tool_request_id VARCHAR(64),
    -- requested_by_type (human/system/agent principal) is deferred to
    -- phase-03 (Security / Separation Of Duties); left nullable rather than
    -- populated with a fake placeholder value until that spec's real actor
    -- model exists.
    requested_by_type VARCHAR(32),
    requested_by_id VARCHAR(128) NOT NULL,
    approval_type VARCHAR(32) NOT NULL,
    risk_level VARCHAR(16) NOT NULL,
    constraints JSONB NOT NULL DEFAULT '[]'::jsonb,
    status VARCHAR(16) NOT NULL,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_approval_requests_source_key UNIQUE (source_domain, source_request_id, request_key),
    CONSTRAINT ck_approval_requests_approval_type CHECK (approval_type IN ('TOOL_EXECUTION', 'TICKET_ACTION', 'WORKFLOW_ACTION', 'POLICY_OVERRIDE', 'GENERIC')),
    CONSTRAINT ck_approval_requests_risk_level CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_approval_requests_status CHECK (status IN ('REQUESTED', 'APPROVED', 'DENIED', 'EXPIRED', 'CANCELLED', 'SUPERSEDED')),
    CONSTRAINT ck_approval_requests_constraints_array CHECK (jsonb_typeof(constraints) = 'array')
);

CREATE INDEX ix_approval_requests_status_expires ON governance.approval_requests (status, expires_at);
CREATE INDEX ix_approval_requests_ticket ON governance.approval_requests (ticket_id);
CREATE INDEX ix_approval_requests_workflow ON governance.approval_requests (workflow_instance_id);
CREATE INDEX ix_approval_requests_tool_request ON governance.approval_requests (tool_request_id);
