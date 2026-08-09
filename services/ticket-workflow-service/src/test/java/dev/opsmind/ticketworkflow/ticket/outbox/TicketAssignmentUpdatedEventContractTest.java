package dev.opsmind.ticketworkflow.ticket.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketAssignmentUpdatedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.EventSchemaValidationException;
import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketAssignmentUpdated;
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

/** SPEC-TW-030 event-contract: mirrors {@code TicketCancelledEventContractTest}'s shape. */
@Tag("contract")
class TicketAssignmentUpdatedEventContractTest {

    private final TicketAssignmentUpdatedEventMapper mapper = new TicketAssignmentUpdatedEventMapper();
    private final JsonSchemaEventValidator validator = new JsonSchemaEventValidator(new ObjectMapper());

    private TicketAssignmentUpdated sampleEvent() {
        return new TicketAssignmentUpdated(
            TicketId.of(UUID.randomUUID()),
            TicketStatus.IN_PROGRESS,
            TicketStatus.IN_PROGRESS,
            "TEAM-A",
            "TEAM-B",
            SupportQueueId.of(UUID.randomUUID()),
            SupportQueueId.of(UUID.randomUUID()),
            "sam.support",
            "alex.support",
            "Rebalancing queue load across teams.",
            "IT_SUPPORT",
            "lead.sam",
            Instant.parse("2026-08-07T23:00:00Z"),
            "SM-039",
            "TICKET_ASSIGNMENT_UPDATED",
            8L,
            Instant.parse("2026-08-07T23:00:00Z")
        );
    }

    @Test
    void shouldProduceEnvelopeFieldsMatchingApprovedEventIdentity() {
        OutboxEventEntry entry = mapper.map(sampleEvent(), "trace-1", "corr-1", "cmd-1");

        assertThat(entry.eventType()).isEqualTo("ticket.assignment-updated");
        assertThat(entry.eventVersion()).isEqualTo("1.0");
        assertThat(entry.routingKey()).isEqualTo("ticket.assignment-updated.v1");
        assertThat(entry.aggregateType()).isEqualTo("Ticket");
        assertThat(entry.aggregateVersion()).isEqualTo(8L);
        assertThat(entry.dataClassification()).isEqualTo("INTERNAL");
        assertThat(entry.payload()).containsKeys(
            "status", "previousTeamId", "newTeamId", "previousSupportQueueId", "newSupportQueueId",
            "previousAssigneeId", "newAssigneeId", "reason", "updatedBy", "updatedAt"
        );
        assertThat(entry.payload().get("newTeamId")).isEqualTo("TEAM-B");
        assertThat(entry.payload().get("newAssigneeId")).isEqualTo("alex.support");
    }

    @Test
    void shouldPassSchemaValidationForAValidPayload() {
        OutboxEventEntry entry = mapper.map(sampleEvent(), "trace-1", "corr-1", "cmd-1");

        validator.validate(entry.eventType(), entry.eventVersion(), entry.payload());
    }

    @Test
    void shouldPassSchemaValidationWhenPreviousFieldsAreNull() {
        TicketAssignmentUpdated unassignedEvent = new TicketAssignmentUpdated(
            TicketId.of(UUID.randomUUID()), TicketStatus.NEW, TicketStatus.NEW, null, "TEAM-B",
            null, SupportQueueId.of(UUID.randomUUID()), null, null, "Initial routing to a team.",
            "SERVICE", "assignment-router", Instant.parse("2026-08-07T23:00:00Z"), "SM-039", "TICKET_ASSIGNMENT_UPDATED",
            1L, Instant.parse("2026-08-07T23:00:00Z")
        );
        OutboxEventEntry entry = mapper.map(unassignedEvent, "trace-1", "corr-1", "cmd-1");

        validator.validate(entry.eventType(), entry.eventVersion(), entry.payload());
    }

    @Test
    void shouldFailSchemaValidationWhenPayloadIsMissingARequiredField() {
        OutboxEventEntry entry = mapper.map(sampleEvent(), "trace-1", "corr-1", "cmd-1");
        HashMap<String, Object> incompletePayload = new HashMap<>(entry.payload());
        incompletePayload.remove("reason");

        assertThatThrownBy(() -> validator.validate(entry.eventType(), entry.eventVersion(), incompletePayload))
            .isInstanceOf(EventSchemaValidationException.class);
    }

    @Test
    void shouldFailSchemaValidationWhenPayloadContainsAnAdditionalField() {
        OutboxEventEntry entry = mapper.map(sampleEvent(), "trace-1", "corr-1", "cmd-1");
        HashMap<String, Object> extendedPayload = new HashMap<>(entry.payload());
        extendedPayload.put("ticketId", UUID.randomUUID().toString());

        assertThatThrownBy(() -> validator.validate(entry.eventType(), entry.eventVersion(), extendedPayload))
            .isInstanceOf(EventSchemaValidationException.class);
    }

    @Test
    void shouldFailSchemaValidationForAMissingNewTeamId() {
        OutboxEventEntry entry = mapper.map(sampleEvent(), "trace-1", "corr-1", "cmd-1");
        HashMap<String, Object> tamperedPayload = new HashMap<>(entry.payload());
        tamperedPayload.put("newTeamId", "");

        assertThatThrownBy(() -> validator.validate(entry.eventType(), entry.eventVersion(), tamperedPayload))
            .isInstanceOf(EventSchemaValidationException.class);
    }
}
