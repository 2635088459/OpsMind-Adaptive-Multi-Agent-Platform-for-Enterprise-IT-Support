package dev.opsmind.ticketworkflow.ticket.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketAssignmentEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.EventSchemaValidationException;
import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketAssigned;
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

/** SPEC-TW-008 event-contract §3: mirrors {@code TicketTriagedEventContractTest}'s 4-test shape exactly. */
@Tag("contract")
class TicketAssignedEventContractTest {

    private final TicketAssignmentEventMapper mapper = new TicketAssignmentEventMapper();
    private final JsonSchemaEventValidator validator = new JsonSchemaEventValidator(new ObjectMapper());

    private TicketAssigned sampleEvent() {
        return new TicketAssigned(
            TicketId.of(UUID.randomUUID()),
            TicketStatus.TRIAGED,
            TicketStatus.ASSIGNED,
            "agent-1",
            "IT_SUPPORT",
            "support-100",
            "Primary endpoint support owner",
            8L,
            Instant.parse("2026-07-31T18:30:00Z")
        );
    }

    private SupportQueueId sampleQueueId() {
        return SupportQueueId.of(UUID.randomUUID());
    }

    @Test
    void shouldProduceEnvelopeFieldsMatchingApprovedEventIdentity() {
        OutboxEventEntry entry = mapper.mapAssigned(sampleEvent(), sampleQueueId(), "trace-1", "corr-1", "cmd-1");

        assertThat(entry.eventType()).isEqualTo("ticket.assigned");
        assertThat(entry.eventVersion()).isEqualTo("1.0");
        assertThat(entry.routingKey()).isEqualTo("ticket.assigned.v1");
        assertThat(entry.aggregateType()).isEqualTo("Ticket");
        assertThat(entry.aggregateVersion()).isEqualTo(8L);
        assertThat(entry.dataClassification()).isEqualTo("INTERNAL");
        assertThat(entry.payload()).containsKeys("supportQueueId", "assigneeId", "previousStatus", "newStatus", "reason");
        assertThat(entry.payload().get("previousStatus")).isEqualTo("TRIAGED");
        assertThat(entry.payload().get("newStatus")).isEqualTo("ASSIGNED");
    }

    @Test
    void shouldPassSchemaValidationForAValidPayload() {
        OutboxEventEntry entry = mapper.mapAssigned(sampleEvent(), sampleQueueId(), "trace-1", "corr-1", "cmd-1");

        validator.validate(entry.eventType(), entry.eventVersion(), entry.payload());
    }

    @Test
    void shouldFailSchemaValidationWhenPayloadIsMissingARequiredField() {
        OutboxEventEntry entry = mapper.mapAssigned(sampleEvent(), sampleQueueId(), "trace-1", "corr-1", "cmd-1");
        HashMap<String, Object> incompletePayload = new HashMap<>(entry.payload());
        incompletePayload.remove("assigneeId");

        assertThatThrownBy(() -> validator.validate(entry.eventType(), entry.eventVersion(), incompletePayload))
            .isInstanceOf(EventSchemaValidationException.class);
    }

    @Test
    void shouldFailSchemaValidationWhenPayloadContainsAnAdditionalField() {
        OutboxEventEntry entry = mapper.mapAssigned(sampleEvent(), sampleQueueId(), "trace-1", "corr-1", "cmd-1");
        HashMap<String, Object> extendedPayload = new HashMap<>(entry.payload());
        extendedPayload.put("actorId", "support-100");

        assertThatThrownBy(() -> validator.validate(entry.eventType(), entry.eventVersion(), extendedPayload))
            .isInstanceOf(EventSchemaValidationException.class);
    }
}
