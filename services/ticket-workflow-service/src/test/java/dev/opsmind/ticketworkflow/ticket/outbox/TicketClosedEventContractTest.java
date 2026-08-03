package dev.opsmind.ticketworkflow.ticket.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketClosedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.EventSchemaValidationException;
import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketClosed;
import dev.opsmind.ticketworkflow.ticket.domain.value.CloseReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
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

/** SPEC-TW-011 event-contract §2: mirrors {@code TicketResolvedEventContractTest}'s shape. */
@Tag("contract")
class TicketClosedEventContractTest {

    private final TicketClosedEventMapper mapper = new TicketClosedEventMapper();
    private final JsonSchemaEventValidator validator = new JsonSchemaEventValidator(new ObjectMapper());

    private TicketClosed sampleEvent() {
        return new TicketClosed(
            TicketId.of(UUID.randomUUID()),
            TicketStatus.RESOLVED,
            TicketStatus.CLOSED,
            "sam.support",
            UUID.randomUUID(),
            CloseReasonCode.REQUESTER_CONFIRMED,
            "Requester confirmed the issue is resolved and no further action is required.",
            "IT_SUPPORT",
            "sam.support",
            Instant.parse("2026-07-31T20:10:00Z"),
            "SM-011",
            "TICKET_CLOSED",
            19L,
            Instant.parse("2026-07-31T20:10:00Z")
        );
    }

    private SupportQueueId sampleQueueId() {
        return SupportQueueId.of(UUID.randomUUID());
    }

    @Test
    void shouldProduceEnvelopeFieldsMatchingApprovedEventIdentity() {
        OutboxEventEntry entry = mapper.map(sampleEvent(), sampleQueueId(), "trace-1", "corr-1", "cmd-1");

        assertThat(entry.eventType()).isEqualTo("ticket.closed");
        assertThat(entry.eventVersion()).isEqualTo("1.0");
        assertThat(entry.routingKey()).isEqualTo("ticket.closed.v1");
        assertThat(entry.aggregateType()).isEqualTo("Ticket");
        assertThat(entry.aggregateVersion()).isEqualTo(19L);
        assertThat(entry.dataClassification()).isEqualTo("INTERNAL");
        assertThat(entry.payload()).containsKeys(
            "supportQueueId", "assigneeId", "resolutionCycleId", "previousStatus", "newStatus",
            "closeReasonCode", "closedBy", "closedAt"
        );
        assertThat(entry.payload().get("previousStatus")).isEqualTo("RESOLVED");
        assertThat(entry.payload().get("newStatus")).isEqualTo("CLOSED");
        assertThat(entry.payload().get("closeReasonCode")).isEqualTo("REQUESTER_CONFIRMED");
    }

    @Test
    void shouldPassSchemaValidationForAValidPayload() {
        OutboxEventEntry entry = mapper.map(sampleEvent(), sampleQueueId(), "trace-1", "corr-1", "cmd-1");

        validator.validate(entry.eventType(), entry.eventVersion(), entry.payload());
    }

    @Test
    void shouldFailSchemaValidationWhenPayloadIsMissingARequiredField() {
        OutboxEventEntry entry = mapper.map(sampleEvent(), sampleQueueId(), "trace-1", "corr-1", "cmd-1");
        HashMap<String, Object> incompletePayload = new HashMap<>(entry.payload());
        incompletePayload.remove("closeReasonCode");

        assertThatThrownBy(() -> validator.validate(entry.eventType(), entry.eventVersion(), incompletePayload))
            .isInstanceOf(EventSchemaValidationException.class);
    }

    @Test
    void shouldFailSchemaValidationWhenPayloadContainsAnAdditionalField() {
        OutboxEventEntry entry = mapper.map(sampleEvent(), sampleQueueId(), "trace-1", "corr-1", "cmd-1");
        HashMap<String, Object> extendedPayload = new HashMap<>(entry.payload());
        extendedPayload.put("closedByType", "IT_SUPPORT");

        assertThatThrownBy(() -> validator.validate(entry.eventType(), entry.eventVersion(), extendedPayload))
            .isInstanceOf(EventSchemaValidationException.class);
    }

    @Test
    void shouldFailSchemaValidationForAnUnsupportedCloseReasonCodeValue() {
        OutboxEventEntry entry = mapper.map(sampleEvent(), sampleQueueId(), "trace-1", "corr-1", "cmd-1");
        HashMap<String, Object> tamperedPayload = new HashMap<>(entry.payload());
        tamperedPayload.put("closeReasonCode", "SOMETHING_ELSE");

        assertThatThrownBy(() -> validator.validate(entry.eventType(), entry.eventVersion(), tamperedPayload))
            .isInstanceOf(EventSchemaValidationException.class);
    }
}
