CREATE SEQUENCE ticket.ticket_display_id_seq
    AS BIGINT
    START WITH 1
    INCREMENT BY 1;

CREATE TABLE ticket.tickets (
    ticket_id UUID PRIMARY KEY,
    display_id VARCHAR(32) NOT NULL,
    requester_id VARCHAR(128) NOT NULL,
    title VARCHAR(200) NOT NULL,
    initial_description TEXT NOT NULL,
    source VARCHAR(32) NOT NULL,
    application_code VARCHAR(64) NOT NULL,
    category VARCHAR(64),
    subcategory VARCHAR(64),
    priority VARCHAR(16) NOT NULL DEFAULT 'UNASSIGNED',
    status VARCHAR(32) NOT NULL,
    current_team_id VARCHAR(64),
    current_support_user_id VARCHAR(128),
    active_workflow_id VARCHAR(64),
    current_resolution_cycle_id UUID NOT NULL,
    auto_close_due_at TIMESTAMPTZ,
    resolved_at TIMESTAMPTZ,
    closed_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    cancel_reason_code VARCHAR(64),
    close_reason_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_by_type VARCHAR(32) NOT NULL,
    created_by_id VARCHAR(128) NOT NULL,

    CONSTRAINT uq_tickets_display_id UNIQUE (display_id),
    CONSTRAINT ck_tickets_title_not_blank CHECK (char_length(btrim(title)) BETWEEN 1 AND 200),
    CONSTRAINT ck_tickets_description_not_blank CHECK (char_length(btrim(initial_description)) BETWEEN 1 AND 10000),
    CONSTRAINT ck_tickets_source CHECK (source IN ('PORTAL', 'EMAIL', 'API', 'SYSTEM')),
    CONSTRAINT ck_tickets_priority CHECK (priority IN ('UNASSIGNED', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_tickets_status CHECK (status IN (
        'NEW', 'TRIAGING', 'INVESTIGATING', 'WAITING_FOR_USER', 'WAITING_FOR_APPROVAL',
        'EXECUTING', 'VERIFYING', 'RESOLVED', 'CLOSED', 'ESCALATED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_tickets_version_nonnegative CHECK (version >= 0),
    CONSTRAINT ck_tickets_created_updated CHECK (updated_at >= created_at),
    CONSTRAINT ck_tickets_support_user_requires_team CHECK (current_support_user_id IS NULL OR current_team_id IS NOT NULL),
    CONSTRAINT ck_tickets_resolved_fields CHECK (status <> 'RESOLVED' OR (resolved_at IS NOT NULL AND auto_close_due_at IS NOT NULL)),
    CONSTRAINT ck_tickets_closed_fields CHECK (status <> 'CLOSED' OR (resolved_at IS NOT NULL AND closed_at IS NOT NULL AND close_reason_code IS NOT NULL AND active_workflow_id IS NULL)),
    CONSTRAINT ck_tickets_cancelled_fields CHECK (status <> 'CANCELLED' OR (cancelled_at IS NOT NULL AND cancel_reason_code IS NOT NULL))
);

CREATE INDEX ix_tickets_requester_created ON ticket.tickets (requester_id, created_at DESC);
CREATE INDEX ix_tickets_status ON ticket.tickets (status);
CREATE INDEX ix_tickets_application_code ON ticket.tickets (application_code);
CREATE INDEX ix_tickets_current_team ON ticket.tickets (current_team_id) WHERE current_team_id IS NOT NULL;
CREATE INDEX ix_tickets_auto_close_due ON ticket.tickets (auto_close_due_at)
    WHERE status = 'RESOLVED' AND auto_close_due_at IS NOT NULL;
CREATE INDEX ix_tickets_active_workflow ON ticket.tickets (active_workflow_id) WHERE active_workflow_id IS NOT NULL;
CREATE INDEX ix_tickets_created_at ON ticket.tickets (created_at);
