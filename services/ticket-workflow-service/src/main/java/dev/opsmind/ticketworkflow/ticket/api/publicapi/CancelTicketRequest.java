package dev.opsmind.ticketworkflow.ticket.api.publicapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.opsmind.ticketworkflow.ticket.domain.value.CancelReasonCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = false)
public record CancelTicketRequest(
    @NotNull
    CancelReasonCode cancelReasonCode,

    @NotBlank
    @Size(min = 3, max = 500)
    String cancelReason
) {
}
