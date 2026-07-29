-- SPEC-TW-007 reference migration for PostgreSQL/Flyway.
-- Reconcile identifiers with Phase 01/02 migrations before applying.

CREATE TABLE IF NOT EXISTS ticket_categories (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    code VARCHAR(80) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_ticket_categories_tenant_code UNIQUE (tenant_id, code),
    CONSTRAINT uq_ticket_categories_tenant_id UNIQUE (tenant_id, id)
);

CREATE TABLE IF NOT EXISTS ticket_subcategories (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    category_id UUID NOT NULL,
    code VARCHAR(80) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_ticket_subcategories_tenant_code
        UNIQUE (tenant_id, category_id, code),
    CONSTRAINT uq_ticket_subcategories_tenant_id
        UNIQUE (tenant_id, id),
    CONSTRAINT fk_ticket_subcategories_category
        FOREIGN KEY (tenant_id, category_id)
        REFERENCES ticket_categories (tenant_id, id)
);

ALTER TABLE tickets
    ADD COLUMN IF NOT EXISTS category_id UUID,
    ADD COLUMN IF NOT EXISTS subcategory_id UUID,
    ADD COLUMN IF NOT EXISTS priority VARCHAR(16),
    ADD COLUMN IF NOT EXISTS support_queue_id UUID,
    ADD COLUMN IF NOT EXISTS triaged_by UUID,
    ADD COLUMN IF NOT EXISTS triaged_at TIMESTAMPTZ;

ALTER TABLE tickets
    DROP CONSTRAINT IF EXISTS ck_tickets_priority;

ALTER TABLE tickets
    ADD CONSTRAINT ck_tickets_priority
    CHECK (priority IS NULL OR priority IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL'));

-- Add these foreign keys only if earlier migrations expose the matching
-- tenant-aware unique keys and table names.
ALTER TABLE tickets
    ADD CONSTRAINT fk_tickets_category
    FOREIGN KEY (tenant_id, category_id)
    REFERENCES ticket_categories (tenant_id, id)
    NOT VALID;

ALTER TABLE tickets
    ADD CONSTRAINT fk_tickets_subcategory
    FOREIGN KEY (tenant_id, subcategory_id)
    REFERENCES ticket_subcategories (tenant_id, id)
    NOT VALID;

CREATE INDEX IF NOT EXISTS idx_ticket_categories_tenant_active
    ON ticket_categories (tenant_id, active);

CREATE INDEX IF NOT EXISTS idx_ticket_subcategories_parent_active
    ON ticket_subcategories (tenant_id, category_id, active);

CREATE INDEX IF NOT EXISTS idx_tickets_triaged_queue
    ON tickets (tenant_id, support_queue_id, updated_at DESC, id)
    WHERE status = 'TRIAGED';

CREATE INDEX IF NOT EXISTS idx_tickets_category
    ON tickets (tenant_id, category_id)
    WHERE category_id IS NOT NULL;

-- Ensure the lifecycle status constraint or PostgreSQL enum already includes
-- TRIAGED. Keep that change in the project's single authoritative status migration.

