-- SPEC-TW-041 Data Integrity Repair: the repair-attempt decision ledger.
--
-- Adapted from the SPEC's reference migration
-- (V041__data_integrity_repair.sql), mirroring SPEC-TW-037 to
-- SPEC-TW-040's own V040-V043 adaptations: the real next Flyway version in
-- this schema is V044. ticket_id is UUID (the internal ticket.tickets
-- primary key) — resolved from the SPEC-TW-037 reconciliation case's own
-- ticket_id, since this endpoint (/internal/v1/tickets/integrity-repairs)
-- is not ticket-scoped in its path. completed_at stays NULL on insert —
-- domain-rules "Repair must first produce a scan finding and repair plan
-- before controlled repair execution" means this SPEC only records that a
-- controlled repair was applied against an existing finding, it never
-- itself closes the underlying case.
CREATE TABLE ticket.ticket_phase10_data_integrity_repair (
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

  CONSTRAINT ck_ticket_phase10_data_integrity_repair_attempt CHECK (attempt_number >= 1),
  CONSTRAINT ck_ticket_phase10_data_integrity_repair_decision CHECK (decision IN ('APPLIED', 'REJECTED'))
);

CREATE INDEX idx_ticket_phase10_data_integrity_repair_ticket_created
  ON ticket.ticket_phase10_data_integrity_repair (ticket_id, created_at DESC);

-- SPEC-TW-041 api-contract §"Errors" 409 CONFLICT guard: the open-attempt
-- check (application §"checkNoOpenAttempt") and the attempt-numbering
-- summary both filter on this exact pair, so a dedicated composite index
-- keeps both reads (and every future insert) index-only.
CREATE INDEX idx_ticket_phase10_data_integrity_repair_source_reference
  ON ticket.ticket_phase10_data_integrity_repair (ticket_id, source_reference, completed_at);
