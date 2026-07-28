CREATE TABLE ticket.ticket_messages (
    message_id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL,
    message_type VARCHAR(32) NOT NULL,
    visibility VARCHAR(16) NOT NULL,
    author_type VARCHAR(32) NOT NULL,
    author_id VARCHAR(128) NOT NULL,
    content TEXT NOT NULL,
    content_format VARCHAR(16) NOT NULL DEFAULT 'PLAIN_TEXT',
    source_command_id VARCHAR(128),
    trace_id VARCHAR(64),
    data_classification VARCHAR(16) NOT NULL DEFAULT 'SENSITIVE',
    created_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT fk_ticket_messages_ticket FOREIGN KEY (ticket_id) REFERENCES ticket.tickets (ticket_id),
    CONSTRAINT ck_ticket_messages_type CHECK (message_type IN ('PUBLIC_REQUESTER_MESSAGE', 'PUBLIC_SUPPORT_MESSAGE', 'INTERNAL_SUPPORT_NOTE')),
    CONSTRAINT ck_ticket_messages_visibility CHECK (visibility IN ('PUBLIC', 'INTERNAL')),
    CONSTRAINT ck_ticket_messages_type_visibility CHECK (
        (message_type IN ('PUBLIC_REQUESTER_MESSAGE', 'PUBLIC_SUPPORT_MESSAGE') AND visibility = 'PUBLIC')
        OR (message_type = 'INTERNAL_SUPPORT_NOTE' AND visibility = 'INTERNAL')
    ),
    CONSTRAINT ck_ticket_messages_content_length CHECK (char_length(btrim(content)) BETWEEN 1 AND 8000),
    CONSTRAINT ck_ticket_messages_version CHECK (version = 0)
);

CREATE INDEX ix_ticket_messages_ticket_created ON ticket.ticket_messages (ticket_id, created_at ASC, message_id ASC);
