package dev.opsmind.ticketworkflow.ticket.api.support;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.opsmind.ticketworkflow.ticket.domain.value.ResolutionCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = false)
public record ResolveTicketRequest(
    @NotNull
    ResolutionCode resolutionCode,

    @NotBlank
    @Size(min = 10, max = 5000)
    String resolutionSummary
) {
}
