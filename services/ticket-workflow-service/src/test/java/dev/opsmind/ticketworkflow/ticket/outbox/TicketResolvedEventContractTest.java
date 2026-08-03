package dev.opsmind.ticketworkflow.ticket.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketResolvedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.EventSchemaValidationException;
import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketResolved;
import dev.opsmind.ticketworkflow.ticket.domain.value.ResolutionCode;
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

/** SPEC-TW-010 event-contract §3: mirrors {@code TicketAssignedEventContractTest}'s shape. */
@Tag("contract")
class TicketResolvedEventContractTest {

    private final TicketResolvedEventMapper mapper = new TicketResolvedEventMapper();
    private final JsonSchemaEventValidator validator = new JsonSchemaEventValidator(new ObjectMapper());

    private TicketResolved sampleEvent() {
        return new TicketResolved(
            TicketId.of(UUID.randomUUID()),
            TicketStatus.IN_PROGRESS,
            TicketStatus.RESOLVED,
            "sam.support",
            UUID.randomUUID(),
            ResolutionCode.FIXED,
            "Reinstalled the endpoint management profile and confirmed the device checked in successfully.",
            "IT_SUPPORT",
            "sam.support",
            Instant.parse("2026-07-31T19:05:00Z"),
            Instant.parse("2026-08-07T19:05:00Z"),
            "SM-010",
            "TICKET_RESOLVED",
            18L,
            Instant.parse("2026-07-31T19:05:00Z")
        );
    }

    private SupportQueueId sampleQueueId() {
        return SupportQueueId.of(UUID.randomUUID());
    }

    @Test
    void shouldProduceEnvelopeFieldsMatchingApprovedEventIdentity() {
        OutboxEventEntry entry = mapper.map(sampleEvent(), sampleQueueId(), "trace-1", "corr-1", "cmd-1");

        assertThat(entry.eventType()).isEqualTo("ticket.resolved");
        assertThat(entry.eventVersion()).isEqualTo("1.0");
        assertThat(entry.routingKey()).isEqualTo("ticket.resolved.v1");
        assertThat(entry.aggregateType()).isEqualTo("Ticket");
        assertThat(entry.aggregateVersion()).isEqualTo(18L);
        assertThat(entry.dataClassification()).isEqualTo("INTERNAL");
        assertThat(entry.payload()).containsKeys(
            "supportQueueId", "assigneeId", "resolutionCycleId", "previousStatus", "newStatus",
            "resolutionCode", "resolutionSummary", "resolvedBy", "resolvedAt", "autoCloseDueAt"
        );
        assertThat(entry.payload().get("previousStatus")).isEqualTo("IN_PROGRESS");
        assertThat(entry.payload().get("newStatus")).isEqualTo("RESOLVED");
        assertThat(entry.payload().get("resolutionCode")).isEqualTo("FIXED");
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
        incompletePayload.remove("resolutionSummary");

        assertThatThrownBy(() -> validator.validate(entry.eventType(), entry.eventVersion(), incompletePayload))
            .isInstanceOf(EventSchemaValidationException.class);
    }

    @Test
    void shouldFailSchemaValidationWhenPayloadContainsAnAdditionalField() {
        OutboxEventEntry entry = mapper.map(sampleEvent(), sampleQueueId(), "trace-1", "corr-1", "cmd-1");
        HashMap<String, Object> extendedPayload = new HashMap<>(entry.payload());
        extendedPayload.put("resolvedByType", "IT_SUPPORT");

        assertThatThrownBy(() -> validator.validate(entry.eventType(), entry.eventVersion(), extendedPayload))
            .isInstanceOf(EventSchemaValidationException.class);
    }

    @Test
    void shouldFailSchemaValidationForAnUnsupportedResolutionCodeValue() {
        OutboxEventEntry entry = mapper.map(sampleEvent(), sampleQueueId(), "trace-1", "corr-1", "cmd-1");
        HashMap<String, Object> tamperedPayload = new HashMap<>(entry.payload());
        tamperedPayload.put("resolutionCode", "SOMETHING_ELSE");

        assertThatThrownBy(() -> validator.validate(entry.eventType(), entry.eventVersion(), tamperedPayload))
            .isInstanceOf(EventSchemaValidationException.class);
    }
}
