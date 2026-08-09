package dev.opsmind.ticketworkflow.ticket.api.support;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.opsmind.ticketworkflow.ticket.domain.value.EscalationReasonCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = false)
public record EscalateTicketRequest(
    @NotNull
    EscalationReasonCode escalationReasonCode,

    @NotBlank
    @Size(min = 3, max = 500)
    String escalationReason
) {
}
