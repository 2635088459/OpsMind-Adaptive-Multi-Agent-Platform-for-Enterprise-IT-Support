package dev.opsmind.ticketworkflow.ticket.api.support;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/v1/tickets/{ticketId}/unassign} request body (API
 * contract §4). {@code assigneeId} is deliberately not a declared field:
 * any such property is rejected as unknown by Jackson's fail-on-unknown
 * setting (API contract §8: "assigneeId is ... forbidden for unassign").
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record UnassignTicketRequest(
    @NotBlank
    @Size(min = 3, max = 500)
    String reason
) {
}
