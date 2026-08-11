-- SPEC-TW-039 Correction Event: the correction-attempt decision ledger.
--
-- Adapted from the SPEC's reference migration (V039__correction_event.sql),
-- mirroring SPEC-TW-037/038's own V040/V041 adaptations: the real next
-- Flyway version in this schema is V042. ticket_id is UUID (the internal
-- ticket.tickets primary key), matching every other
-- /internal/v1/tickets/{ticketId}/... endpoint (SPEC-TW-025/027/037).
-- completed_at stays NULL on insert — domain-rules "Correction events must
-- not delete or rewrite original events": this SPEC only publishes the
-- corrective fact, it never mutates or closes the original history.
CREATE TABLE ticket.ticket_phase10_correction_event (
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

  CONSTRAINT ck_ticket_phase10_correction_event_attempt CHECK (attempt_number >= 1),
  CONSTRAINT ck_ticket_phase10_correction_event_decision CHECK (decision IN ('APPLIED', 'REJECTED'))
);

CREATE INDEX idx_ticket_phase10_correction_event_ticket_created
  ON ticket.ticket_phase10_correction_event (ticket_id, created_at DESC);

-- SPEC-TW-039 api-contract §"Errors" 409 CONFLICT guard: the open-attempt
-- check (application §"checkNoOpenAttempt") and the attempt-numbering
-- summary both filter on this exact pair, so a dedicated composite index
-- keeps both reads (and every future insert) index-only.
CREATE INDEX idx_ticket_phase10_correction_event_source_reference
  ON ticket.ticket_phase10_correction_event (ticket_id, source_reference, completed_at);
