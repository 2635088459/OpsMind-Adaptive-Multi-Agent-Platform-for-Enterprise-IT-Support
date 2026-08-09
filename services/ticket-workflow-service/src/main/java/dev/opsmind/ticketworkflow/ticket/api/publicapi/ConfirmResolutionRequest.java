package dev.opsmind.ticketworkflow.ticket.api.publicapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.opsmind.ticketworkflow.ticket.domain.value.ResolutionConfirmationReasonCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = false)
public record ConfirmResolutionRequest(
    @NotNull
    ResolutionConfirmationReasonCode reasonCode,

    @NotBlank
    @Size(min = 3, max = 500)
    String reason
) {
}
