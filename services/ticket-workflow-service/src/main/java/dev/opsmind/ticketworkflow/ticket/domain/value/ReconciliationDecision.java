package dev.opsmind.ticketworkflow.ticket.domain.value;

/**
 * SPEC-TW-037 api-contract §"Response 200"/event-contract: the outcome of a
 * single open-reconciliation-case attempt. {@code APPLIED} is the only
 * decision ever returned in a {@code 200} response body or published in
 * {@code ticket.reconciliation-case-opened.v1} — a rejected attempt throws
 * instead (acceptance-criteria "Rejected paths do not publish success
 * events"), but {@code REJECTED} is still part of this vocabulary so the
 * decision ledger (persistence §"Recommended Table" {@code decision} column)
 * can record it if a future recovery phase (SPEC-TW-038 to SPEC-TW-041)
 * needs to.
 */
public enum ReconciliationDecision {
    APPLIED,
    REJECTED
}
