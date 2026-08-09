package dev.opsmind.ticketworkflow.ticket.api.internalapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * SPEC-TW-027 API contract: no {@code reasonCode} field — unlike
 * SPEC-TW-026, auto-close has exactly one valid business reason ({@code
 * CloseReasonCode.AUTO_CLOSE_TIMEOUT}), which {@code
 * AutoCloseTicketApplicationService} supplies itself rather than trusting
 * the scheduler to pick one.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record AutoCloseTicketRequest(
    @NotBlank
    @Size(min = 3, max = 500)
    String reason
) {
}
