package dev.opsmind.ticketworkflow.ticket.domain.value;

/**
 * SPEC-TW-026 API contract: the controlled reason vocabulary for confirming
 * a resolution — deliberately a narrower subset of {@link CloseReasonCode}
 * (which also covers administrative closures like {@code
 * AUTO_CLOSE_TIMEOUT} or {@code DUPLICATE_CLOSED} that are not a
 * "confirmation" at all). Binding the request directly to this narrower
 * enum rejects any other value with a clean {@code 400 VALIDATION_ERROR} at
 * deserialization, before the command ever reaches the Application layer.
 * Persisted through the same {@code close_reason_code} column {@link
 * CloseReasonCode} already writes (both enums share these two literal
 * names), since a confirmed resolution is still, physically, a close.
 */
public enum ResolutionConfirmationReasonCode {
    REQUESTER_CONFIRMED,
    SUPPORT_CONFIRMED
}
