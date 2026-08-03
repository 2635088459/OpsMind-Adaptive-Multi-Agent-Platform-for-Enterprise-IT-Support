package dev.opsmind.ticketworkflow.ticket.api.publicapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * SPEC-TW-013 API contract declares {@code body} as 1-10000 characters, but
 * every reply is persisted through the shared {@code TicketMessage}
 * aggregate (SPEC-TW-004), whose {@code MessageContent} invariant — already
 * enforced at both the domain layer and the {@code ticket_messages}
 * {@code ck_ticket_messages_content_length} CHECK constraint — caps content
 * at 8000. This bound matches that actual system-wide ceiling rather than
 * the spec's literal 10000, so a request between 8001-10000 fails here with
 * a clean {@code 400 VALIDATION_ERROR} instead of an unhandled 500 once it
 * reaches {@code MessageContent}.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record UserReplyRequest(
    @NotBlank
    @Size(max = 8000)
    String body,

    @Size(max = 20)
    List<String> attachmentIds
) {
}
