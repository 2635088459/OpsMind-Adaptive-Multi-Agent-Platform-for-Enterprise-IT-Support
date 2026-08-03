package dev.opsmind.ticketworkflow.ticket.api.support;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Shared request shape for {@code POST /api/v1/tickets/{ticketId}/assign}
 * and {@code .../reassign} (identical fields, API contract §2-3).
 * {@code assigneeId} is a plain identity string, not a UUID: this
 * codebase represents every actor/subject identity (requester, triager,
 * support user) as an opaque string matching a JWT {@code sub} claim
 * (e.g. {@code current_support_user_id VARCHAR(128)}), never a UUID type
 * — a deliberate deviation from the spec's UUID-formatted example.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record AssignTicketRequest(
    @NotBlank
    String assigneeId,

    @NotBlank
    @Size(min = 3, max = 500)
    String reason
) {
}
