package dev.opsmind.ticketworkflow.ticket.api.internalapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = false)
public record StartVerificationRequest(
    @NotBlank
    @Size(max = 128)
    String toolResultId,

    @NotBlank
    @Size(max = 64)
    String verificationType,

    @Size(max = 2000)
    String reason
) {
}
