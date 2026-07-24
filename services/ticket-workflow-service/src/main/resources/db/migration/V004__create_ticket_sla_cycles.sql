CREATE TABLE ticket.ticket_sla_cycles (
    sla_cycle_id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL,
    resolution_cycle_id UUID NOT NULL,
    policy_id VARCHAR(64) NOT NULL,
    cycle_number INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL,
    response_due_at TIMESTAMPTZ,
    resolution_due_at TIMESTAMPTZ,
    paused_at TIMESTAMPTZ,
    accumulated_paused_seconds BIGINT NOT NULL DEFAULT 0,
    breached_at TIMESTAMPTZ,
    met_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT fk_sla_ticket FOREIGN KEY (ticket_id) REFERENCES ticket.tickets (ticket_id),
    CONSTRAINT fk_sla_resolution_cycle FOREIGN KEY (resolution_cycle_id) REFERENCES ticket.ticket_resolution_cycles (resolution_cycle_id),
    CONSTRAINT uq_sla_cycle_number UNIQUE (ticket_id, cycle_number),
    CONSTRAINT uq_sla_resolution_cycle UNIQUE (resolution_cycle_id),
    CONSTRAINT ck_sla_status CHECK (status IN ('ACTIVE', 'PAUSED', 'MET', 'BREACHED', 'CANCELLED')),
    CONSTRAINT ck_sla_pause_seconds CHECK (accumulated_paused_seconds >= 0),
    CONSTRAINT ck_sla_time_order CHECK (resolution_due_at IS NULL OR resolution_due_at >= created_at),
    CONSTRAINT ck_sla_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uq_ticket_one_active_sla_cycle
    ON ticket.ticket_sla_cycles (ticket_id)
    WHERE status IN ('ACTIVE', 'PAUSED', 'BREACHED');

CREATE INDEX ix_sla_cycles_ticket ON ticket.ticket_sla_cycles (ticket_id);
