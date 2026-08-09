-- Reference migration for SPEC-TW-036 Step-up Authentication
-- Final implementation may reuse ticket.audit_records when that is sufficient.

CREATE TABLE IF NOT EXISTS ticket.phase9_step_up_authentication_decisions (
  id UUID PRIMARY KEY,
  ticket_id UUID,
  actor_id VARCHAR(128) NOT NULL,
  actor_type VARCHAR(64) NOT NULL,
  operation VARCHAR(128) NOT NULL,
  decision VARCHAR(32) NOT NULL,
  decision_code VARCHAR(128) NOT NULL,
  correlation_id VARCHAR(128),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT ck_phase9_step_up_authentication_decision CHECK (decision IN ('ALLOW', 'DENY', 'FAIL_CLOSED'))
);

CREATE INDEX IF NOT EXISTS idx_phase9_step_up_authentication_ticket_created
  ON ticket.phase9_step_up_authentication_decisions (ticket_id, created_at DESC);
