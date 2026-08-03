package dev.opsmind.ticketworkflow.ticket.api.support;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = false)
public record TransitionTicketStatusRequest(
    @NotNull
    TicketStatus targetStatus,

    @NotBlank
    @Size(min = 3, max = 500)
    String reason,

    Instant waitingForRequesterSince,

    @Size(min = 3, max = 128)
    String approvalReference
) {
}
