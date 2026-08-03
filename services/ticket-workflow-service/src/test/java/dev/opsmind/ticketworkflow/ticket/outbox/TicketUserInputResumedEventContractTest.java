package dev.opsmind.ticketworkflow.ticket.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketUserInputResumedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.EventSchemaValidationException;
import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketUserInputResumed;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import dev.opsmind.ticketworkflow.ticket.infrastructure.event.JsonSchemaEventValidator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SPEC-TW-013 event-contract: one domain fact maps to two distinct Outbox entries. */
@Tag("contract")
class TicketUserInputResumedEventContractTest {

    private final TicketUserInputResumedEventMapper mapper = new TicketUserInputResumedEventMapper();
    private final JsonSchemaEventValidator validator = new JsonSchemaEventValidator(new ObjectMapper());

    private TicketUserInputResumed sampleEvent() {
        return new TicketUserInputResumed(
            TicketId.of(UUID.randomUUID()),
            TicketStatus.WAITING_FOR_USER,
            TicketStatus.IN_PROGRESS,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "EMPLOYEE",
            "alice",
            Instant.parse("2026-08-03T19:15:00Z"),
            "SM-015",
            "USER_REPLIED",
            22L,
            Instant.parse("2026-08-03T19:15:00Z")
        );
    }

    @Test
    void shouldProduceAReplyReceivedEntryOmittingTheMessageBody() {
        OutboxEventEntry entry = mapper.mapReplyReceived(sampleEvent(), "trace-1", "corr-1", "cmd-1");

        assertThat(entry.eventType()).isEqualTo("ticket.user-reply-received");
        assertThat(entry.routingKey()).isEqualTo("ticket.user-reply-received.v1");
        assertThat(entry.aggregateVersion()).isEqualTo(22L);
        assertThat(entry.payload()).containsOnlyKeys("requestId", "messageId", "repliedBy", "repliedAt");
        validator.validate(entry.eventType(), entry.eventVersion(), entry.payload());
    }

    @Test
    void shouldProduceAResumedEntryWithTransitionMetadata() {
        OutboxEventEntry entry = mapper.mapResumed(sampleEvent(), "trace-1", "corr-1", "cmd-1");

        assertThat(entry.eventType()).isEqualTo("ticket.user-input-resumed");
        assertThat(entry.routingKey()).isEqualTo("ticket.user-input-resumed.v1");
        assertThat(entry.payload().get("previousStatus")).isEqualTo("WAITING_FOR_USER");
        assertThat(entry.payload().get("newStatus")).isEqualTo("IN_PROGRESS");
        assertThat(entry.payload().get("resumeReason")).isEqualTo("USER_REPLIED");
        validator.validate(entry.eventType(), entry.eventVersion(), entry.payload());
    }

    @Test
    void shouldFailSchemaValidationWhenReplyReceivedPayloadIsMissingARequiredField() {
        OutboxEventEntry entry = mapper.mapReplyReceived(sampleEvent(), "trace-1", "corr-1", "cmd-1");
        HashMap<String, Object> incomplete = new HashMap<>(entry.payload());
        incomplete.remove("messageId");

        assertThatThrownBy(() -> validator.validate(entry.eventType(), entry.eventVersion(), incomplete))
            .isInstanceOf(EventSchemaValidationException.class);
    }

    @Test
    void shouldFailSchemaValidationWhenResumedPayloadContainsAnAdditionalField() {
        OutboxEventEntry entry = mapper.mapResumed(sampleEvent(), "trace-1", "corr-1", "cmd-1");
        HashMap<String, Object> extended = new HashMap<>(entry.payload());
        extended.put("repliedBy", "alice");

        assertThatThrownBy(() -> validator.validate(entry.eventType(), entry.eventVersion(), extended))
            .isInstanceOf(EventSchemaValidationException.class);
    }
}
