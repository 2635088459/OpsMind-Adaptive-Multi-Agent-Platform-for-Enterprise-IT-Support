-- SPEC-TW-040 Compensation: the compensation-attempt decision ledger.
--
-- Adapted from the SPEC's reference migration (V040__compensation.sql),
-- mirroring SPEC-TW-037/038/039's own V040/V041/V042 adaptations: the real
-- next Flyway version in this schema is V043. ticket_id is UUID (the
-- internal ticket.tickets primary key), matching every other
-- /internal/v1/tickets/{ticketId}/... endpoint (SPEC-TW-025/027/037/039).
-- Adds compensation_action beyond the SPEC's own reference columns —
-- domain-rules "Compensation must select a defined action and cannot run
-- arbitrary SQL or arbitrary state mutation" requires a fixed, CHECK-
-- constrained vocabulary, not a free-form action string. completed_at stays
-- NULL on insert: this SPEC only records that a compensating action was
-- executed, it never itself repairs the ticket's business state.
CREATE TABLE ticket.ticket_phase10_compensation (
  id UUID PRIMARY KEY,
  ticket_id UUID,
  compensation_action VARCHAR(64) NOT NULL,
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

  CONSTRAINT ck_ticket_phase10_compensation_attempt CHECK (attempt_number >= 1),
  CONSTRAINT ck_ticket_phase10_compensation_decision CHECK (decision IN ('APPLIED', 'REJECTED')),
  CONSTRAINT ck_ticket_phase10_compensation_action CHECK (
    compensation_action IN ('RETRY_SIDE_EFFECT', 'REVERSE_SIDE_EFFECT', 'ACKNOWLEDGE_SIDE_EFFECT', 'MARK_MANUALLY_RECONCILED')
  )
);

CREATE INDEX idx_ticket_phase10_compensation_ticket_created
  ON ticket.ticket_phase10_compensation (ticket_id, created_at DESC);

-- SPEC-TW-040 api-contract §"Errors" 409 CONFLICT guard: the open-attempt
-- check (application §"checkNoOpenAttempt") and the attempt-numbering
-- summary both filter on this exact pair, so a dedicated composite index
-- keeps both reads (and every future insert) index-only.
CREATE INDEX idx_ticket_phase10_compensation_source_reference
  ON ticket.ticket_phase10_compensation (ticket_id, source_reference, completed_at);
