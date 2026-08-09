package dev.opsmind.ticketworkflow.ticket.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketEscalatedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.EventSchemaValidationException;
import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketEscalated;
import dev.opsmind.ticketworkflow.ticket.domain.value.EscalationReasonCode;
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

/** SPEC-TW-031 event-contract: mirrors {@code TicketCancelledEventContractTest}'s shape. */
@Tag("contract")
class TicketEscalatedEventContractTest {

    private final TicketEscalatedEventMapper mapper = new TicketEscalatedEventMapper();
    private final JsonSchemaEventValidator validator = new JsonSchemaEventValidator(new ObjectMapper());

    private TicketEscalated sampleEvent() {
        return new TicketEscalated(
            TicketId.of(UUID.randomUUID()),
            TicketStatus.IN_PROGRESS,
            TicketStatus.ESCALATED,
            "TEAM-A",
            SupportQueueId.of(UUID.randomUUID()),
            "sam.support",
            UUID.randomUUID(),
            "wf-42",
            EscalationReasonCode.USER_IMPACT,
            "Customer-facing outage with broad user impact.",
            "IT_SUPPORT",
            "lead.sam",
            Instant.parse("2026-08-07T23:00:00Z"),
            "SM-043",
            "TICKET_ESCALATED",
            8L,
            Instant.parse("2026-08-07T23:00:00Z")
        );
    }

    @Test
    void shouldProduceEnvelopeFieldsMatchingApprovedEventIdentity() {
        OutboxEventEntry entry = mapper.map(sampleEvent(), "trace-1", "corr-1", "cmd-1");

        assertThat(entry.eventType()).isEqualTo("ticket.escalated");
        assertThat(entry.eventVersion()).isEqualTo("1.0");
        assertThat(entry.routingKey()).isEqualTo("ticket.escalated.v1");
        assertThat(entry.aggregateType()).isEqualTo("Ticket");
        assertThat(entry.aggregateVersion()).isEqualTo(8L);
        assertThat(entry.dataClassification()).isEqualTo("INTERNAL");
        assertThat(entry.payload()).containsKeys(
            "teamId", "supportQueueId", "assigneeId", "resolutionCycleId", "workflowId",
            "previousStatus", "newStatus", "escalationReasonCode", "escalationReason", "escalatedBy", "escalatedAt"
        );
        assertThat(entry.payload().get("newStatus")).isEqualTo("ESCALATED");
        assertThat(entry.payload().get("escalationReasonCode")).isEqualTo("USER_IMPACT");
    }

    @Test
    void shouldPassSchemaValidationForAValidPayload() {
        OutboxEventEntry entry = mapper.map(sampleEvent(), "trace-1", "corr-1", "cmd-1");

        validator.validate(entry.eventType(), entry.eventVersion(), entry.payload());
    }

    @Test
    void shouldPassSchemaValidationWhenTeamQueueAssigneeAndWorkflowAreNull() {
        TicketEscalated unassignedEvent = new TicketEscalated(
            TicketId.of(UUID.randomUUID()), TicketStatus.NEW, TicketStatus.ESCALATED, null, null, null,
            UUID.randomUUID(), null, EscalationReasonCode.POLICY_REQUIRED, "Compliance mandates manual review.",
            "SERVICE", "escalation-policy-worker", Instant.parse("2026-08-07T23:00:00Z"), "SM-040", "TICKET_ESCALATED",
            1L, Instant.parse("2026-08-07T23:00:00Z")
        );
        OutboxEventEntry entry = mapper.map(unassignedEvent, "trace-1", "corr-1", "cmd-1");

        validator.validate(entry.eventType(), entry.eventVersion(), entry.payload());
    }

    @Test
    void shouldFailSchemaValidationWhenPayloadIsMissingARequiredField() {
        OutboxEventEntry entry = mapper.map(sampleEvent(), "trace-1", "corr-1", "cmd-1");
        HashMap<String, Object> incompletePayload = new HashMap<>(entry.payload());
        incompletePayload.remove("escalationReason");

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
    void shouldFailSchemaValidationForAnInvalidEscalationReasonCode() {
        OutboxEventEntry entry = mapper.map(sampleEvent(), "trace-1", "corr-1", "cmd-1");
        HashMap<String, Object> tamperedPayload = new HashMap<>(entry.payload());
        tamperedPayload.put("escalationReasonCode", "NOT_A_REAL_CODE");

        assertThatThrownBy(() -> validator.validate(entry.eventType(), entry.eventVersion(), tamperedPayload))
            .isInstanceOf(EventSchemaValidationException.class);
    }
}
