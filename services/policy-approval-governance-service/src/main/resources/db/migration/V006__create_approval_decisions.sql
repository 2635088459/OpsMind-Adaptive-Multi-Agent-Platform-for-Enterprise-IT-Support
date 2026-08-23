CREATE TABLE governance.approval_decisions (
    approval_decision_id VARCHAR(64) PRIMARY KEY,
    approval_request_id VARCHAR(64) NOT NULL REFERENCES governance.approval_requests (approval_request_id),
    decision VARCHAR(16) NOT NULL,
    -- decided_by_type: same deferral as approval_requests.requested_by_type.
    decided_by_type VARCHAR(32),
    decided_by_id VARCHAR(128) NOT NULL,
    reason TEXT NOT NULL,
    conditions JSONB NOT NULL DEFAULT '[]'::jsonb,
    separation_of_duties_result BOOLEAN NOT NULL,
    decided_at TIMESTAMPTZ NOT NULL,

    -- One final decision per approval request (07-data-model).
    CONSTRAINT uq_approval_decisions_request UNIQUE (approval_request_id),
    CONSTRAINT ck_approval_decisions_decision CHECK (decision IN ('APPROVED', 'DENIED')),
    CONSTRAINT ck_approval_decisions_conditions_array CHECK (jsonb_typeof(conditions) = 'array')
);
