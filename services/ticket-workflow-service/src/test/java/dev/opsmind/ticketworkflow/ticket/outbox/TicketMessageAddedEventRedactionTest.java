package dev.opsmind.ticketworkflow.ticket.outbox;

import dev.opsmind.ticketworkflow.ticket.application.event.TicketMessageAddedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.domain.message.MessageVisibility;
import dev.opsmind.ticketworkflow.ticket.domain.message.TicketMessageAdded;
import dev.opsmind.ticketworkflow.ticket.domain.message.TicketMessageId;
import dev.opsmind.ticketworkflow.ticket.domain.message.TicketMessageType;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-TW-004 §15: the ticket.message.added.v1 payload excludes content,
 * title, description, raw author ID, email, JWT, and Idempotency Key.
 */
@Tag("contract")
class TicketMessageAddedEventRedactionTest {

    private static final String DISTINCTIVE_CONTENT = "CONTENT_MARKER_should_never_appear_in_the_event";
    private static final String RAW_AUTHOR_ID = "employee-123";

    private final TicketMessageAddedEventMapper mapper = new TicketMessageAddedEventMapper();

    @Test
    void shouldExcludeContentAndRawAuthorIdFromPayload() {
        TicketMessageAdded event = new TicketMessageAdded(
            TicketMessageId.of(UUID.randomUUID()), TicketId.of(UUID.randomUUID()),
            TicketMessageType.PUBLIC_REQUESTER_MESSAGE, MessageVisibility.PUBLIC, "EMPLOYEE",
            Instant.parse("2026-07-25T18:30:00Z")
        );

        OutboxEventEntry entry = mapper.map(event, "trace-1", "corr-1", "cmd-1");
        Map<String, Object> payload = entry.payload();

        assertThat(payload).doesNotContainKeys(
            "content", "title", "description", "authorId", "requesterId", "email",
            "jwt", "authorizationHeader", "idempotencyKey", "password", "accessToken"
        );
        assertThat(payload).containsOnlyKeys("messageId", "ticketId", "messageType", "visibility", "authorType", "createdAt");
        assertThat(payload.toString()).doesNotContain(DISTINCTIVE_CONTENT);
        assertThat(payload.toString()).doesNotContain(RAW_AUTHOR_ID);
    }
}
