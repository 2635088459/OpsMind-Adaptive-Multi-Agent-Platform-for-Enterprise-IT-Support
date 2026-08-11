package dev.opsmind.ticketworkflow.ticket.api.internalapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.opsmind.ticketworkflow.ticket.domain.value.CompensationAction;
import dev.opsmind.ticketworkflow.ticket.domain.value.ReconciliationReasonCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * SPEC-TW-040 api-contract §"Request", adapted the same way {@code
 * OpenReconciliationCaseRequest} (SPEC-TW-037) adapts its own spec template
 * (idempotency/actor/correlation carried by headers and JWT, not the body),
 * extended with {@code compensationAction}: domain-rules "Compensation must
 * select a defined action" — an unrecognized value fails Jackson
 * deserialization (400), never reaching {@link CompensationAction}.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ExecuteCompensationRequest(
    @NotNull
    CompensationAction compensationAction,

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
