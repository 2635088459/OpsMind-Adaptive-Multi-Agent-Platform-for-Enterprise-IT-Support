package dev.opsmind.ticketworkflow.ticket.api.support;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * {@code POST /api/v1/tickets/{ticketId}/triage} request body (SPEC-TW-007
 * API contract). {@code triagedBy}, {@code triagedAt}, {@code status}, and
 * {@code version} are never accepted: any such field is rejected as an
 * unknown property by Jackson's fail-on-unknown-properties setting before
 * this type is even constructed, since none are declared here (AC-13).
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record TriageTicketRequest(
    @NotNull
    UUID categoryId,

    UUID subcategoryId,

    @NotBlank
    String priority,

    @NotNull
    UUID supportQueueId,

    @NotBlank
    @Size(min = 1, max = 500)
    String reason
) {
}
