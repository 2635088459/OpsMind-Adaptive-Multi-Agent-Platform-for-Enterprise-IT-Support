package dev.opsmind.ticketworkflow.ticket.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketAssignmentEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.EventSchemaValidationException;
import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketUnassigned;
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

/** SPEC-TW-008 event-contract §5: mirrors {@code TicketTriagedEventContractTest}'s 4-test shape exactly. */
@Tag("contract")
class TicketUnassignedEventContractTest {

    private final TicketAssignmentEventMapper mapper = new TicketAssignmentEventMapper();
    private final JsonSchemaEventValidator validator = new JsonSchemaEventValidator(new ObjectMapper());

    private TicketUnassigned sampleEvent() {
        return new TicketUnassigned(
            TicketId.of(UUID.randomUUID()),
            TicketStatus.ASSIGNED,
            TicketStatus.TRIAGED,
            "agent-1",
            "IT_SUPPORT",
            "support-100",
            "Agent left the support rotation",
            10L,
            Instant.parse("2026-07-31T18:30:00Z")
        );
    }

    private SupportQueueId sampleQueueId() {
        return SupportQueueId.of(UUID.randomUUID());
    }

    @Test
    void shouldProduceEnvelopeFieldsMatchingApprovedEventIdentity() {
        OutboxEventEntry entry = mapper.mapUnassigned(sampleEvent(), sampleQueueId(), "trace-1", "corr-1", "cmd-1");

        assertThat(entry.eventType()).isEqualTo("ticket.unassigned");
        assertThat(entry.eventVersion()).isEqualTo("1.0");
        assertThat(entry.routingKey()).isEqualTo("ticket.unassigned.v1");
        assertThat(entry.aggregateType()).isEqualTo("Ticket");
        assertThat(entry.aggregateVersion()).isEqualTo(10L);
        assertThat(entry.dataClassification()).isEqualTo("INTERNAL");
        assertThat(entry.payload()).containsKeys("supportQueueId", "previousAssigneeId", "previousStatus", "newStatus", "reason");
        assertThat(entry.payload().get("previousStatus")).isEqualTo("ASSIGNED");
        assertThat(entry.payload().get("newStatus")).isEqualTo("TRIAGED");
        // No fabricated new owner (event-contract §5 assertions): the payload never carries an "assigneeId" key.
        assertThat(entry.payload()).doesNotContainKey("assigneeId");
    }

    @Test
    void shouldPassSchemaValidationForAValidPayload() {
        OutboxEventEntry entry = mapper.mapUnassigned(sampleEvent(), sampleQueueId(), "trace-1", "corr-1", "cmd-1");

        validator.validate(entry.eventType(), entry.eventVersion(), entry.payload());
    }

    @Test
    void shouldFailSchemaValidationWhenPayloadIsMissingARequiredField() {
        OutboxEventEntry entry = mapper.mapUnassigned(sampleEvent(), sampleQueueId(), "trace-1", "corr-1", "cmd-1");
        HashMap<String, Object> incompletePayload = new HashMap<>(entry.payload());
        incompletePayload.remove("previousAssigneeId");

        assertThatThrownBy(() -> validator.validate(entry.eventType(), entry.eventVersion(), incompletePayload))
            .isInstanceOf(EventSchemaValidationException.class);
    }

    @Test
    void shouldFailSchemaValidationWhenPayloadContainsAnAdditionalField() {
        OutboxEventEntry entry = mapper.mapUnassigned(sampleEvent(), sampleQueueId(), "trace-1", "corr-1", "cmd-1");
        HashMap<String, Object> extendedPayload = new HashMap<>(entry.payload());
        extendedPayload.put("assigneeId", "leaked-fabricated-new-owner");

        assertThatThrownBy(() -> validator.validate(entry.eventType(), entry.eventVersion(), extendedPayload))
            .isInstanceOf(EventSchemaValidationException.class);
    }
}
