package dev.opsmind.ticketworkflow.ticket.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketCancelledEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.EventSchemaValidationException;
import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketCancelled;
import dev.opsmind.ticketworkflow.ticket.domain.value.CancelReasonCode;
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

/** SPEC-TW-029 event-contract: mirrors {@code TicketResolutionConfirmedEventContractTest}'s shape. */
@Tag("contract")
class TicketCancelledEventContractTest {

    private final TicketCancelledEventMapper mapper = new TicketCancelledEventMapper();
    private final JsonSchemaEventValidator validator = new JsonSchemaEventValidator(new ObjectMapper());

    private TicketCancelled sampleEvent() {
        return new TicketCancelled(
            TicketId.of(UUID.randomUUID()),
            TicketStatus.IN_PROGRESS,
            TicketStatus.CANCELLED,
            "sam.support",
            UUID.randomUUID(),
            CancelReasonCode.NO_LONGER_NEEDED,
            "The requester no longer needs this request.",
            "EMPLOYEE",
            "employee-123",
            Instant.parse("2026-08-07T22:00:00Z"),
            "SM-034",
            "TICKET_CANCELLED",
            6L,
            Instant.parse("2026-08-07T22:00:00Z")
        );
    }

    private SupportQueueId sampleQueueId() {
        return SupportQueueId.of(UUID.randomUUID());
    }

    @Test
    void shouldProduceEnvelopeFieldsMatchingApprovedEventIdentity() {
        OutboxEventEntry entry = mapper.map(sampleEvent(), sampleQueueId(), "trace-1", "corr-1", "cmd-1");

        assertThat(entry.eventType()).isEqualTo("ticket.cancelled");
        assertThat(entry.eventVersion()).isEqualTo("1.0");
        assertThat(entry.routingKey()).isEqualTo("ticket.cancelled.v1");
        assertThat(entry.aggregateType()).isEqualTo("Ticket");
        assertThat(entry.aggregateVersion()).isEqualTo(6L);
        assertThat(entry.dataClassification()).isEqualTo("INTERNAL");
        assertThat(entry.payload()).containsKeys(
            "supportQueueId", "assigneeId", "resolutionCycleId", "previousStatus", "newStatus",
            "cancelReasonCode", "cancelReason", "cancelledBy", "cancelledAt"
        );
        assertThat(entry.payload().get("previousStatus")).isEqualTo("IN_PROGRESS");
        assertThat(entry.payload().get("newStatus")).isEqualTo("CANCELLED");
        assertThat(entry.payload().get("cancelReasonCode")).isEqualTo("NO_LONGER_NEEDED");
    }

    @Test
    void shouldPassSchemaValidationForAValidPayload() {
        OutboxEventEntry entry = mapper.map(sampleEvent(), sampleQueueId(), "trace-1", "corr-1", "cmd-1");

        validator.validate(entry.eventType(), entry.eventVersion(), entry.payload());
    }

    @Test
    void shouldPassSchemaValidationWhenTheAssigneeIsNull() {
        TicketCancelled unassignedEvent = new TicketCancelled(
            TicketId.of(UUID.randomUUID()), TicketStatus.NEW, TicketStatus.CANCELLED, null, UUID.randomUUID(),
            CancelReasonCode.REQUESTER_CANCELLED, "Created by mistake, please cancel.", "EMPLOYEE", "employee-123",
            Instant.parse("2026-08-07T22:00:00Z"), "SM-033", "TICKET_CANCELLED", 1L, Instant.parse("2026-08-07T22:00:00Z")
        );
        OutboxEventEntry entry = mapper.map(unassignedEvent, sampleQueueId(), "trace-1", "corr-1", "cmd-1");

        validator.validate(entry.eventType(), entry.eventVersion(), entry.payload());
    }

    @Test
    void shouldFailSchemaValidationWhenPayloadIsMissingARequiredField() {
        OutboxEventEntry entry = mapper.map(sampleEvent(), sampleQueueId(), "trace-1", "corr-1", "cmd-1");
        HashMap<String, Object> incompletePayload = new HashMap<>(entry.payload());
        incompletePayload.remove("cancelReason");

        assertThatThrownBy(() -> validator.validate(entry.eventType(), entry.eventVersion(), incompletePayload))
            .isInstanceOf(EventSchemaValidationException.class);
    }

    @Test
    void shouldFailSchemaValidationWhenPayloadContainsAnAdditionalField() {
        OutboxEventEntry entry = mapper.map(sampleEvent(), sampleQueueId(), "trace-1", "corr-1", "cmd-1");
        HashMap<String, Object> extendedPayload = new HashMap<>(entry.payload());
        extendedPayload.put("cancelledByType", "EMPLOYEE");

        assertThatThrownBy(() -> validator.validate(entry.eventType(), entry.eventVersion(), extendedPayload))
            .isInstanceOf(EventSchemaValidationException.class);
    }

    @Test
    void shouldFailSchemaValidationForAnUnsupportedCancelReasonCodeValue() {
        OutboxEventEntry entry = mapper.map(sampleEvent(), sampleQueueId(), "trace-1", "corr-1", "cmd-1");
        HashMap<String, Object> tamperedPayload = new HashMap<>(entry.payload());
        tamperedPayload.put("cancelReasonCode", "SOMETHING_ELSE");

        assertThatThrownBy(() -> validator.validate(entry.eventType(), entry.eventVersion(), tamperedPayload))
            .isInstanceOf(EventSchemaValidationException.class);
    }

    @Test
    void shouldFailSchemaValidationForAnUnsupportedPreviousStatusValue() {
        OutboxEventEntry entry = mapper.map(sampleEvent(), sampleQueueId(), "trace-1", "corr-1", "cmd-1");
        HashMap<String, Object> tamperedPayload = new HashMap<>(entry.payload());
        tamperedPayload.put("previousStatus", "CLOSED");

        assertThatThrownBy(() -> validator.validate(entry.eventType(), entry.eventVersion(), tamperedPayload))
            .isInstanceOf(EventSchemaValidationException.class);
    }
}
