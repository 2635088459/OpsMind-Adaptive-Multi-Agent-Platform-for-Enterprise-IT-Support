CREATE TABLE ticket.ticket_resolution_cycles (
    resolution_cycle_id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL,
    cycle_number INTEGER NOT NULL,
    workflow_id VARCHAR(64),
    sla_cycle_id UUID,
    cycle_status VARCHAR(24) NOT NULL,
    opened_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    closed_at TIMESTAMPTZ,
    reopened_at TIMESTAMPTZ,
    reopen_reason_code VARCHAR(64),
    reopened_by_type VARCHAR(32),
    reopened_by_id VARCHAR(128),
    resolution_code VARCHAR(64),
    root_cause_code VARCHAR(64),
    resolution_summary TEXT,
    verification_id VARCHAR(64),
    resolution_attempt_id VARCHAR(64),
    resolved_by_type VARCHAR(32),
    resolved_by_id VARCHAR(128),
    close_reason_code VARCHAR(64),
    closed_by_type VARCHAR(32),
    closed_by_id VARCHAR(128),

    CONSTRAINT fk_resolution_cycle_ticket FOREIGN KEY (ticket_id) REFERENCES ticket.tickets (ticket_id),
    CONSTRAINT uq_resolution_cycle_number UNIQUE (ticket_id, cycle_number),
    CONSTRAINT ck_resolution_cycle_number CHECK (cycle_number >= 1),
    CONSTRAINT ck_resolution_cycle_status CHECK (cycle_status IN ('ACTIVE', 'RESOLVED', 'CLOSED', 'REOPENED', 'CANCELLED')),
    CONSTRAINT ck_resolution_cycle_resolved CHECK (
        cycle_status NOT IN ('RESOLVED', 'CLOSED', 'REOPENED')
        OR (resolved_at IS NOT NULL AND resolution_code IS NOT NULL AND root_cause_code IS NOT NULL AND verification_id IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_ticket_one_active_resolution_cycle
    ON ticket.ticket_resolution_cycles (ticket_id)
    WHERE cycle_status = 'ACTIVE';

CREATE INDEX ix_resolution_cycles_ticket ON ticket.ticket_resolution_cycles (ticket_id);
