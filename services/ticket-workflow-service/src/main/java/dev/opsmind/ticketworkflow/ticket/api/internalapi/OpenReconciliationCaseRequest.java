package dev.opsmind.ticketworkflow.ticket.api.internalapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.opsmind.ticketworkflow.ticket.domain.value.ReconciliationReasonCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * SPEC-TW-037 api-contract §"Request", adapted the same way {@code
 * AutoCloseTicketRequest} (SPEC-TW-027) adapts its own spec template:
 * {@code idempotencyKey}, {@code actorId}, and {@code correlationId} are
 * carried by the {@code Idempotency-Key}/{@code X-Correlation-Id} headers
 * and the caller's own JWT, not duplicated in the body.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record OpenReconciliationCaseRequest(
    @NotNull
    ReconciliationReasonCode reasonCode,

    @NotBlank
    @Size(min = 3, max = 2000)
    String reason,

    @NotBlank
    @Size(min = 1, max = 256)
    String sourceReference
) {
}
