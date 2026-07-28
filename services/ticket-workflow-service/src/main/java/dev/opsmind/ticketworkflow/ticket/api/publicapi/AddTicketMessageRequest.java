package dev.opsmind.ticketworkflow.ticket.api.publicapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Shared request shape for both Employee and Support callers of {@code
 * POST /api/v1/tickets/{ticketId}/messages} — the single physical route
 * (SPEC-TW-004 §4). {@code messageType} must be {@code null} for an
 * Employee and one of {@code PUBLIC_SUPPORT_MESSAGE}/{@code
 * INTERNAL_SUPPORT_NOTE} for Support; {@link PublicTicketMessageApiMapper}
 * enforces that split explicitly. Any other field (visibility, authorId,
 * version, ...) is rejected by Jackson's fail-on-unknown-properties setting
 * before this type is even constructed, since it is not declared here.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record AddTicketMessageRequest(
    @NotBlank
    @Size(max = 8000)
    String content,

    String messageType
) {
}
