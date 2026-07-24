package dev.opsmind.ticketworkflow.ticket.api.publicapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketSource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The only fields an Employee may submit. Deliberately excludes ticketId,
 * displayId, requesterId, status, priority, category, subcategory,
 * assignedTeam, assignedAgent, workflowId, approvalId, resolutionCycleId,
 * slaCycleId, createdAt, updatedAt, version, and attachmentIds; any of those
 * present in the request body is rejected by Jackson's
 * fail-on-unknown-properties setting before this type is even constructed
 * (SPEC-TW-001 §7).
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CreateTicketRequest(
    @NotBlank
    @Size(max = 200)
    String title,

    @NotBlank
    @Size(max = 10_000)
    String description,

    @NotNull
    ApplicationCode applicationCode,

    @NotNull
    TicketSource source
) {
}
