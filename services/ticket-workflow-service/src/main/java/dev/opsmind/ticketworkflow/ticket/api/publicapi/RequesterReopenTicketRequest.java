package dev.opsmind.ticketworkflow.ticket.api.publicapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.opsmind.ticketworkflow.ticket.domain.value.ReopenReasonCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = false)
public record RequesterReopenTicketRequest(
    @NotNull
    ReopenReasonCode reopenReasonCode,

    @NotBlank
    @Size(min = 10, max = 1000)
    String reopenReason
) {
}
