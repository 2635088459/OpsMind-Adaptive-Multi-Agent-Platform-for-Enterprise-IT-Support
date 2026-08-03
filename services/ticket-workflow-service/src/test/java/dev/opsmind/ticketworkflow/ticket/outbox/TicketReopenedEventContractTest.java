package dev.opsmind.ticketworkflow.ticket.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketReopenedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.EventSchemaValidationException;
import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketReopened;
import dev.opsmind.ticketworkflow.ticket.domain.value.OwnershipStatus;
import dev.opsmind.ticketworkflow.ticket.domain.value.ReopenReasonCode;
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

/** SPEC-TW-011 event-contract §3: mirrors {@code TicketClosedEventContractTest}'s shape. */
@Tag("contract")
class TicketReopenedEventContractTest {

    private final TicketReopenedEventMapper mapper = new TicketReopenedEventMapper();
    private final JsonSchemaEventValidator validator = new JsonSchemaEventValidator(new ObjectMapper());

    private TicketReopened sampleEvent(String assigneeId, OwnershipStatus ownershipStatus) {
        return new TicketReopened(
            TicketId.of(UUID.randomUUID()),
            TicketStatus.CLOSED,
            TicketStatus.IN_PROGRESS,
            assigneeId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            2,
            ReopenReasonCode.ISSUE_RECURRED,
            "The requester reported the same endpoint enrollment failure returned after reboot.",
            "IT_SUPPORT",
            "sam.support",
            Instant.parse("2026-07-31T21:30:00Z"),
            1,
            ownershipStatus,
            "SM-013",
            "TICKET_REOPENED",
            20L,
            Instant.parse("2026-07-31T21:30:00Z")
        );
    }

    private SupportQueueId sampleQueueId() {
        return SupportQueueId.of(UUID.randomUUID());
    }

    @Test
    void shouldProduceEnvelopeFieldsMatchingApprovedEventIdentity() {
        OutboxEventEntry entry = mapper.map(sampleEvent("sam.support", OwnershipStatus.ACTIVE), sampleQueueId(), "trace-1", "corr-1", "cmd-1");

        assertThat(entry.eventType()).isEqualTo("ticket.reopened");
        assertThat(entry.eventVersion()).isEqualTo("1.0");
        assertThat(entry.routingKey()).isEqualTo("ticket.reopened.v1");
        assertThat(entry.aggregateType()).isEqualTo("Ticket");
        assertThat(entry.aggregateVersion()).isEqualTo(20L);
        assertThat(entry.dataClassification()).isEqualTo("INTERNAL");
        assertThat(entry.payload()).containsKeys(
            "supportQueueId", "assigneeId", "previousResolutionCycleId", "newResolutionCycleId", "previousStatus",
            "newStatus", "reopenReasonCode", "reopenCount", "reopenedBy", "reopenedAt", "ownershipStatus"
        );
        assertThat(entry.payload().get("previousStatus")).isEqualTo("CLOSED");
        assertThat(entry.payload().get("newStatus")).isEqualTo("IN_PROGRESS");
        assertThat(entry.payload().get("ownershipStatus")).isEqualTo("ACTIVE");
    }

    @Test
    void shouldPassSchemaValidationForAValidPayload() {
        OutboxEventEntry entry = mapper.map(sampleEvent("sam.support", OwnershipStatus.ACTIVE), sampleQueueId(), "trace-1", "corr-1", "cmd-1");

        validator.validate(entry.eventType(), entry.eventVersion(), entry.payload());
    }

    @Test
    void shouldPassSchemaValidationWithANullAssigneeIdForAnUnassignedTicket() {
        OutboxEventEntry entry = mapper.map(sampleEvent(null, OwnershipStatus.UNASSIGNED), sampleQueueId(), "trace-1", "corr-1", "cmd-1");

        validator.validate(entry.eventType(), entry.eventVersion(), entry.payload());
    }

    @Test
    void shouldFailSchemaValidationWhenPayloadIsMissingARequiredField() {
        OutboxEventEntry entry = mapper.map(sampleEvent("sam.support", OwnershipStatus.ACTIVE), sampleQueueId(), "trace-1", "corr-1", "cmd-1");
        HashMap<String, Object> incompletePayload = new HashMap<>(entry.payload());
        incompletePayload.remove("reopenCount");

        assertThatThrownBy(() -> validator.validate(entry.eventType(), entry.eventVersion(), incompletePayload))
            .isInstanceOf(EventSchemaValidationException.class);
    }

    @Test
    void shouldFailSchemaValidationWhenPayloadContainsAnAdditionalField() {
        OutboxEventEntry entry = mapper.map(sampleEvent("sam.support", OwnershipStatus.ACTIVE), sampleQueueId(), "trace-1", "corr-1", "cmd-1");
        HashMap<String, Object> extendedPayload = new HashMap<>(entry.payload());
        extendedPayload.put("reopenedByType", "IT_SUPPORT");

        assertThatThrownBy(() -> validator.validate(entry.eventType(), entry.eventVersion(), extendedPayload))
            .isInstanceOf(EventSchemaValidationException.class);
    }

    @Test
    void shouldFailSchemaValidationForAnUnsupportedOwnershipStatusValue() {
        OutboxEventEntry entry = mapper.map(sampleEvent("sam.support", OwnershipStatus.ACTIVE), sampleQueueId(), "trace-1", "corr-1", "cmd-1");
        HashMap<String, Object> tamperedPayload = new HashMap<>(entry.payload());
        tamperedPayload.put("ownershipStatus", "SOMETHING_ELSE");

        assertThatThrownBy(() -> validator.validate(entry.eventType(), entry.eventVersion(), tamperedPayload))
            .isInstanceOf(EventSchemaValidationException.class);
    }
}
