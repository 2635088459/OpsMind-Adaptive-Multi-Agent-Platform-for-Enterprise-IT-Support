-- SPEC-TW-038 Replay Event: the replay-attempt decision ledger.
--
-- Adapted from the SPEC's reference migration (V038__replay_event.sql),
-- mirroring SPEC-TW-037's own V040 adaptation: the real next Flyway version
-- in this schema is V041. ticket_id is UUID (the internal ticket.tickets
-- primary key) — resolved from the original event's own ticket_id, since
-- this endpoint (/internal/v1/tickets/events/replay) is not ticket-scoped
-- in its path. completed_at stays NULL on insert — SPEC-TW-038 only records
-- that a replay was applied (domain-rules: "must not directly repair
-- business state"); closing an attempt is a later recovery phase's
-- responsibility.
CREATE TABLE ticket.ticket_phase10_replay_event (
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

  CONSTRAINT ck_ticket_phase10_replay_event_attempt CHECK (attempt_number >= 1),
  CONSTRAINT ck_ticket_phase10_replay_event_decision CHECK (decision IN ('APPLIED', 'REJECTED'))
);

CREATE INDEX idx_ticket_phase10_replay_event_ticket_created
  ON ticket.ticket_phase10_replay_event (ticket_id, created_at DESC);

-- SPEC-TW-038 api-contract §"Errors" 409 CONFLICT guard: the open-attempt
-- check (application §"checkNoOpenAttempt") and the attempt-numbering
-- summary both filter on this exact pair, so a dedicated composite index
-- keeps both reads (and every future insert) index-only.
CREATE INDEX idx_ticket_phase10_replay_event_source_reference
  ON ticket.ticket_phase10_replay_event (ticket_id, source_reference, completed_at);
