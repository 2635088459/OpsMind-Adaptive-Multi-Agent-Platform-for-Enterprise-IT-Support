package dev.opsmind.ticketworkflow.ticket.api.support;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = false)
public record RequestUserInputRequest(
    @NotBlank
    @Size(min = 10, max = 2000)
    String prompt,

    @Size(max = 20)
    List<String> requestedFields,

    Instant expiresAt
) {
}
