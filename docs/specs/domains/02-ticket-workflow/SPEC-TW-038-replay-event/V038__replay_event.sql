-- Reference migration for SPEC-TW-038 Replay Event
-- Final implementation may split records by case, attempt, and audit tables.

CREATE TABLE IF NOT EXISTS ticket.ticket_phase10_replay_event (
  id UUID PRIMARY KEY,
  ticket_id UUID,
  source_reference VARCHAR(256) NOT NULL,
  decision VARCHAR(64) NOT NULL,
  reason_code VARCHAR(128) NOT NULL,
  reason TEXT NOT NULL,
  actor_id VARCHAR(128) NOT NULL,
  correlation_id VARCHAR(128),
  causation_id VARCHAR(128),
  attempt_number INTEGER NOT NULL DEFAULT 1,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  completed_at TIMESTAMP WITH TIME ZONE,
  CONSTRAINT ck_ticket_phase10_replay_event_attempt CHECK (attempt_number >= 1)
);

CREATE INDEX IF NOT EXISTS idx_ticket_phase10_replay_event_ticket_created
  ON ticket.ticket_phase10_replay_event (ticket_id, created_at DESC);
