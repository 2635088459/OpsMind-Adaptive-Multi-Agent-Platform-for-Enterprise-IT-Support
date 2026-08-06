package dev.opsmind.ticketworkflow.ticket.application.command;

/**
 * SPEC-TW-019 API contract §"Outcomes": the four business-processing
 * results (the two DLQ outcomes are consumer-level rejections raised before
 * the use case is ever invoked). {@code REJECTED_BUSINESS_RULE} is part of
 * the shared outcome vocabulary across SPEC-TW-019..021's tool-result
 * consumers; this spec's own domain-rules define no business rule beyond
 * matching/duplicate/stale, so {@link
 * dev.opsmind.ticketworkflow.ticket.application.service.ApplyToolExecutionCompletedApplicationService}
 * never actually returns it, but the value is kept for contract parity.
 */
public enum ApplyToolExecutionCompletedOutcome {
    APPLIED,
    DUPLICATE,
    STALE,
    REJECTED_BUSINESS_RULE
}
