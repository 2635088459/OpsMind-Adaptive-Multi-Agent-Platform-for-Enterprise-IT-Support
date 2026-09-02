-- SPEC-EP-010/011's own "shared attachments capability" — the real schema behind
-- 01-domain-model's own Attachment entity (09-employee-portal):
--   attachmentId / filename / mimeType / sizeBytes / objectRef / thumbnailUrl / uploadStatus
-- `object_key` here is the real MinIO object key `objectRef` points at (an opaque
-- reference, per that model's own comment — the API never exposes the raw key).
CREATE SCHEMA IF NOT EXISTS attachment;

CREATE TABLE attachment.attachments (
    attachment_id UUID PRIMARY KEY,
    filename VARCHAR(255) NOT NULL,
    mime_type VARCHAR(127) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    object_key VARCHAR(512) NOT NULL,
    thumbnail_url VARCHAR(1024),
    -- SPEC-EP-010 03-state-machine §3.2 names UPLOADING/READY/FAILED, but this
    -- service's own upload endpoint is synchronous end-to-end (validate -> store
    -- to MinIO -> respond) — a row is only ever persisted once storage genuinely
    -- succeeds, so READY is the only value this v1 ever actually writes. The
    -- column stays real (not collapsed to a boolean) for a future async/chunked
    -- upload path to use UPLOADING/FAILED against, without a schema change.
    upload_status VARCHAR(16) NOT NULL DEFAULT 'READY'
        CHECK (upload_status IN ('UPLOADING', 'READY', 'FAILED')),
    uploaded_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- SPEC-ARO-039's own multimodal follow-up, and any future "my uploads" listing —
-- both real, foreseeable read patterns scoped to one uploader.
CREATE INDEX idx_attachments_uploaded_by ON attachment.attachments (uploaded_by);
