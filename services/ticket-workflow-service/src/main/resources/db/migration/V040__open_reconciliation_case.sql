-- SPEC-TW-037 Open Reconciliation Case: the recovery-attempt decision ledger.
--
-- Adapted from the SPEC's reference migration
-- (V037__open_reconciliation_case.sql), mirroring SPEC-TW-033 to
-- SPEC-TW-036's own V036-V039 adaptations: the real next Flyway version in
-- this schema is V040. ticket_id is UUID (not the display id) because this
-- endpoint's path variable is the internal ticket.tickets primary key,
-- matching every other /internal/v1/tickets/{ticketId}/... endpoint
-- (SPEC-TW-025/027). completed_at stays NULL on insert — SPEC-TW-037 only
-- opens a case (domain-rules: "must not directly repair business state");
-- closing one is a later recovery phase's responsibility (SPEC-TW-038 to
-- SPEC-TW-041).
CREATE TABLE ticket.ticket_phase10_open_reconciliation_case (
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

  CONSTRAINT ck_ticket_phase10_open_reconciliation_case_attempt CHECK (attempt_number >= 1),
  CONSTRAINT ck_ticket_phase10_open_reconciliation_case_decision CHECK (decision IN ('APPLIED', 'REJECTED'))
);

CREATE INDEX idx_ticket_phase10_open_reconciliation_case_ticket_created
  ON ticket.ticket_phase10_open_reconciliation_case (ticket_id, created_at DESC);

-- SPEC-TW-037 api-contract §"Errors" 409 CONFLICT guard: the open-case check
-- (application §"checkNoOpenCase") and the attempt-numbering summary both
-- filter on this exact pair, so a dedicated composite index keeps both
-- reads (and every future insert) index-only.
CREATE INDEX idx_ticket_phase10_open_reconciliation_case_source_reference
  ON ticket.ticket_phase10_open_reconciliation_case (ticket_id, source_reference, completed_at);
