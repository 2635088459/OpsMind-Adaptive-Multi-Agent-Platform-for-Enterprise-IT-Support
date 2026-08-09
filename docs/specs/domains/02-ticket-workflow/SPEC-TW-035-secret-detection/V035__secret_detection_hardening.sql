-- Reference migration for SPEC-TW-035 Secret Detection
-- Final implementation may reuse ticket.audit_records when that is sufficient.

CREATE TABLE IF NOT EXISTS ticket.phase9_secret_detection_decisions (
  id UUID PRIMARY KEY,
  ticket_id UUID,
  actor_id VARCHAR(128) NOT NULL,
  actor_type VARCHAR(64) NOT NULL,
  operation VARCHAR(128) NOT NULL,
  decision VARCHAR(32) NOT NULL,
  decision_code VARCHAR(128) NOT NULL,
  correlation_id VARCHAR(128),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT ck_phase9_secret_detection_decision CHECK (decision IN ('ALLOW', 'DENY', 'FAIL_CLOSED'))
);

CREATE INDEX IF NOT EXISTS idx_phase9_secret_detection_ticket_created
  ON ticket.phase9_secret_detection_decisions (ticket_id, created_at DESC);
