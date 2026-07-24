CREATE TABLE ticket.ticket_status_history (
    history_id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL,
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    transition_id VARCHAR(16) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    actor_type VARCHAR(32) NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    source_command_id VARCHAR(128),
    source_event_id VARCHAR(64),
    workflow_id VARCHAR(64),
    aggregate_version BIGINT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_ticket_status_history_ticket FOREIGN KEY (ticket_id) REFERENCES ticket.tickets (ticket_id),
    CONSTRAINT uq_ticket_status_history_version UNIQUE (ticket_id, aggregate_version),
    CONSTRAINT ck_ticket_status_history_version CHECK (aggregate_version >= 0)
);

CREATE INDEX ix_ticket_status_history_ticket_time
    ON ticket.ticket_status_history (ticket_id, occurred_at ASC, history_id ASC);
CREATE INDEX ix_ticket_status_history_source_event
    ON ticket.ticket_status_history (source_event_id)
    WHERE source_event_id IS NOT NULL;
