package dev.opsmind.ticketworkflow.ticket.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketUserInputRequestedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.EventSchemaValidationException;
import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketUserInputRequested;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import dev.opsmind.ticketworkflow.ticket.infrastructure.event.JsonSchemaEventValidator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SPEC-TW-012 event-contract: mirrors {@code TicketClosedEventContractTest}'s shape. */
@Tag("contract")
class TicketUserInputRequestedEventContractTest {

    private final TicketUserInputRequestedEventMapper mapper = new TicketUserInputRequestedEventMapper();
    private final JsonSchemaEventValidator validator = new JsonSchemaEventValidator(new ObjectMapper());

    private TicketUserInputRequested sampleEvent(Instant expiresAt) {
        return new TicketUserInputRequested(
            TicketId.of(UUID.randomUUID()),
            TicketStatus.IN_PROGRESS,
            TicketStatus.WAITING_FOR_USER,
            "sam.support",
            UUID.randomUUID(),
            "Please upload a screenshot of the error and confirm whether the laptop is connected to VPN.",
            List.of("screenshot", "vpnStatus"),
            "IT_SUPPORT",
            "sam.support",
            Instant.parse("2026-08-03T18:00:00Z"),
            Instant.parse("2026-08-03T18:00:00Z"),
            "IN_PROGRESS",
            expiresAt,
            "SM-014",
            "USER_INPUT_REQUIRED",
            21L,
            Instant.parse("2026-08-03T18:00:00Z")
        );
    }

    private SupportQueueId sampleQueueId() {
        return SupportQueueId.of(UUID.randomUUID());
    }

    @Test
    void shouldProduceEnvelopeFieldsMatchingApprovedEventIdentityAndOmitThePrompt() {
        OutboxEventEntry entry = mapper.map(sampleEvent(Instant.parse("2026-08-05T18:00:00Z")), sampleQueueId(), "trace-1", "corr-1", "cmd-1");

        assertThat(entry.eventType()).isEqualTo("ticket.user-input-requested");
        assertThat(entry.eventVersion()).isEqualTo("1.0");
        assertThat(entry.routingKey()).isEqualTo("ticket.user-input-requested.v1");
        assertThat(entry.aggregateType()).isEqualTo("Ticket");
        assertThat(entry.aggregateVersion()).isEqualTo(21L);
        assertThat(entry.dataClassification()).isEqualTo("INTERNAL");
        assertThat(entry.payload()).containsKeys(
            "supportQueueId", "assigneeId", "requestId", "previousStatus", "newStatus",
            "requestedBy", "requestedAt", "waitingForRequesterSince", "resumeStatus", "expiresAt"
        );
        assertThat(entry.payload()).doesNotContainKey("prompt");
        assertThat(entry.payload().get("previousStatus")).isEqualTo("IN_PROGRESS");
        assertThat(entry.payload().get("newStatus")).isEqualTo("WAITING_FOR_USER");
    }

    @Test
    void shouldPassSchemaValidationForAValidPayload() {
        OutboxEventEntry entry = mapper.map(sampleEvent(Instant.parse("2026-08-05T18:00:00Z")), sampleQueueId(), "trace-1", "corr-1", "cmd-1");

        validator.validate(entry.eventType(), entry.eventVersion(), entry.payload());
    }

    @Test
    void shouldPassSchemaValidationWithoutAnExpiresAt() {
        OutboxEventEntry entry = mapper.map(sampleEvent(null), sampleQueueId(), "trace-1", "corr-1", "cmd-1");

        validator.validate(entry.eventType(), entry.eventVersion(), entry.payload());
    }

    @Test
    void shouldFailSchemaValidationWhenPayloadIsMissingARequiredField() {
        OutboxEventEntry entry = mapper.map(sampleEvent(null), sampleQueueId(), "trace-1", "corr-1", "cmd-1");
        HashMap<String, Object> incompletePayload = new HashMap<>(entry.payload());
        incompletePayload.remove("requestId");

        assertThatThrownBy(() -> validator.validate(entry.eventType(), entry.eventVersion(), incompletePayload))
            .isInstanceOf(EventSchemaValidationException.class);
    }

    @Test
    void shouldFailSchemaValidationWhenPayloadContainsAnAdditionalField() {
        OutboxEventEntry entry = mapper.map(sampleEvent(null), sampleQueueId(), "trace-1", "corr-1", "cmd-1");
        HashMap<String, Object> extendedPayload = new HashMap<>(entry.payload());
        extendedPayload.put("prompt", "leaked prompt text");

        assertThatThrownBy(() -> validator.validate(entry.eventType(), entry.eventVersion(), extendedPayload))
            .isInstanceOf(EventSchemaValidationException.class);
    }
}
