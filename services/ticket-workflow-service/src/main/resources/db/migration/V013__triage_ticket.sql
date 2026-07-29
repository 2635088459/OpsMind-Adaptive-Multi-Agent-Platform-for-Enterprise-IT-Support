-- SPEC-TW-007 (Triage Ticket): classification/priority/support-queue
-- catalogs plus the Ticket columns Triage sets. Renumbered from the spec
-- folder's reference "V007" to the next real slot in this service's own
-- Flyway sequence (V001-V012 already exist); the reference migration's
-- tenant_id columns/constraints are dropped throughout since no tenant
-- concept exists anywhere else in this schema (see SupportQueueScope's
-- Javadoc for the same, earlier documented deviation).

CREATE TABLE ticket.ticket_categories (
    category_id UUID PRIMARY KEY,
    code VARCHAR(80) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_ticket_categories_code UNIQUE (code)
);

CREATE TABLE ticket.ticket_subcategories (
    subcategory_id UUID PRIMARY KEY,
    category_id UUID NOT NULL,
    code VARCHAR(80) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_ticket_subcategories_category
        FOREIGN KEY (category_id) REFERENCES ticket.ticket_categories (category_id),
    CONSTRAINT uq_ticket_subcategories_category_code UNIQUE (category_id, code)
);

CREATE INDEX ix_ticket_subcategories_category_active
    ON ticket.ticket_subcategories (category_id, active);

-- The routing "queue" a Triaged ticket is dispatched to. `team_id` is the
-- join key already used, but never populated, by SPEC-TW-005's Support
-- Queue authorization (ticket.tickets.current_team_id, the JWT
-- `support_teams` claim): Triage is the first command that ever sets
-- current_team_id, activating that existing-but-dormant column.
CREATE TABLE ticket.support_queues (
    support_queue_id UUID PRIMARY KEY,
    team_id VARCHAR(64) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_support_queues_team_id UNIQUE (team_id)
);

ALTER TABLE ticket.tickets
    ADD COLUMN category_id UUID,
    ADD COLUMN subcategory_id UUID,
    ADD COLUMN support_queue_id UUID,
    ADD COLUMN triaged_by VARCHAR(128),
    ADD COLUMN triaged_at TIMESTAMPTZ;

ALTER TABLE ticket.tickets
    ADD CONSTRAINT fk_tickets_category
        FOREIGN KEY (category_id) REFERENCES ticket.ticket_categories (category_id),
    ADD CONSTRAINT fk_tickets_subcategory
        FOREIGN KEY (subcategory_id) REFERENCES ticket.ticket_subcategories (subcategory_id),
    ADD CONSTRAINT fk_tickets_support_queue
        FOREIGN KEY (support_queue_id) REFERENCES ticket.support_queues (support_queue_id);

-- TRIAGED is new; every value already accepted (including the legacy
-- TRIAGING/INVESTIGATING/EXECUTING/VERIFYING/ESCALATED/FAILED set from an
-- earlier, more granular workflow model) is preserved unchanged and
-- untouched by this spec.
ALTER TABLE ticket.tickets
    DROP CONSTRAINT ck_tickets_status;

ALTER TABLE ticket.tickets
    ADD CONSTRAINT ck_tickets_status CHECK (status IN (
        'NEW', 'TRIAGED', 'TRIAGING', 'INVESTIGATING', 'WAITING_FOR_USER', 'WAITING_FOR_APPROVAL',
        'EXECUTING', 'VERIFYING', 'RESOLVED', 'CLOSED', 'ESCALATED', 'FAILED', 'CANCELLED'));

ALTER TABLE ticket.tickets
    ADD CONSTRAINT ck_tickets_triaged_fields CHECK (
        status <> 'TRIAGED' OR (
            category_id IS NOT NULL
            AND support_queue_id IS NOT NULL
            AND triaged_by IS NOT NULL
            AND triaged_at IS NOT NULL
            AND priority <> 'UNASSIGNED'
        )
    );

CREATE INDEX ix_tickets_support_queue ON ticket.tickets (support_queue_id) WHERE support_queue_id IS NOT NULL;
CREATE INDEX ix_tickets_category ON ticket.tickets (category_id) WHERE category_id IS NOT NULL;
