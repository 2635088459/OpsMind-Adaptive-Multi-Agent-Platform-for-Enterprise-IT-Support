package dev.opsmind.ticketworkflow.ticket.api.internalapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.opsmind.ticketworkflow.ticket.domain.value.ReconciliationReasonCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * SPEC-TW-041 api-contract §"Request", adapted the same way {@code
 * ReplayEventRequest} (SPEC-TW-038) adapts its own spec template:
 * {@code idempotencyKey}, {@code actorId}, and {@code correlationId} are
 * carried by the {@code Idempotency-Key}/{@code X-Correlation-Id} headers
 * and the caller's own JWT, not duplicated in the body. {@code
 * sourceReference} identifies the scan finding to repair (this
 * implementation resolves it against a SPEC-TW-037 reconciliation case id).
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ApplyDataIntegrityRepairRequest(
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
