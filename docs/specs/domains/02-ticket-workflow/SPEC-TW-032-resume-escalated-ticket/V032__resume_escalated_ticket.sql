-- Reference migration for SPEC-TW-032 Resume Escalated Ticket
-- Final implementation must align with the canonical migration naming/order in the service.

CREATE TABLE IF NOT EXISTS ticket_phase8_resume_escalated_ticket_audit (
  id UUID PRIMARY KEY,
  ticket_id UUID NOT NULL,
  workflow_version BIGINT NOT NULL,
  actor_id VARCHAR(128) NOT NULL,
  reason_code VARCHAR(64) NOT NULL,
  reason TEXT NOT NULL,
  from_state VARCHAR(64) NOT NULL,
  to_state VARCHAR(64) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  correlation_id VARCHAR(128),
  causation_id VARCHAR(128),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  UNIQUE (ticket_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_ticket_phase8_resume_escalated_ticket_audit_ticket_id
  ON ticket_phase8_resume_escalated_ticket_audit (ticket_id, created_at DESC);
