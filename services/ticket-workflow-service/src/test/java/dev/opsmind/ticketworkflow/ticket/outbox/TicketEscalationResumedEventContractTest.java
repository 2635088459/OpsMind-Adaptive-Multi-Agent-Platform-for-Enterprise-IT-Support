package dev.opsmind.ticketworkflow.ticket.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketEscalationResumedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.EventSchemaValidationException;
import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketEscalationResumed;
import dev.opsmind.ticketworkflow.ticket.domain.value.EscalationResumeReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.OwnershipStatus;
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

/** SPEC-TW-032 event-contract: mirrors {@code TicketEscalatedEventContractTest}'s shape. */
@Tag("contract")
class TicketEscalationResumedEventContractTest {

    private final TicketEscalationResumedEventMapper mapper = new TicketEscalationResumedEventMapper();
    private final JsonSchemaEventValidator validator = new JsonSchemaEventValidator(new ObjectMapper());

    private TicketEscalationResumed sampleEvent() {
        return new TicketEscalationResumed(
            TicketId.of(UUID.randomUUID()),
            TicketStatus.ESCALATED,
            TicketStatus.IN_PROGRESS,
            "TEAM-A",
            SupportQueueId.of(UUID.randomUUID()),
            "sam.support",
            UUID.randomUUID(),
            EscalationResumeReasonCode.ROOT_CAUSE_RESOLVED,
            "Root cause identified and mitigated; resuming active work.",
            "IT_SUPPORT",
            "lead.sam",
            Instant.parse("2026-08-08T23:00:00Z"),
            OwnershipStatus.ACTIVE,
            "SM-049",
            "TICKET_ESCALATION_RESUMED",
            8L,
            Instant.parse("2026-08-08T23:00:00Z")
        );
    }

    @Test
    void shouldProduceEnvelopeFieldsMatchingApprovedEventIdentity() {
        OutboxEventEntry entry = mapper.map(sampleEvent(), "trace-1", "corr-1", "cmd-1");

        assertThat(entry.eventType()).isEqualTo("ticket.escalation-resumed");
        assertThat(entry.eventVersion()).isEqualTo("1.0");
        assertThat(entry.routingKey()).isEqualTo("ticket.escalation-resumed.v1");
        assertThat(entry.aggregateType()).isEqualTo("Ticket");
        assertThat(entry.aggregateVersion()).isEqualTo(8L);
        assertThat(entry.dataClassification()).isEqualTo("INTERNAL");
        assertThat(entry.payload()).containsKeys(
            "teamId", "supportQueueId", "assigneeId", "resolutionCycleId",
            "previousStatus", "newStatus", "resumeReasonCode", "resumeReason", "resumedBy", "resumedAt", "ownershipStatus"
        );
        assertThat(entry.payload().get("newStatus")).isEqualTo("IN_PROGRESS");
        assertThat(entry.payload().get("resumeReasonCode")).isEqualTo("ROOT_CAUSE_RESOLVED");
    }

    @Test
    void shouldPassSchemaValidationForAValidPayload() {
        OutboxEventEntry entry = mapper.map(sampleEvent(), "trace-1", "corr-1", "cmd-1");

        validator.validate(entry.eventType(), entry.eventVersion(), entry.payload());
    }

    @Test
    void shouldPassSchemaValidationWhenTeamQueueAndAssigneeAreNull() {
        TicketEscalationResumed unassignedEvent = new TicketEscalationResumed(
            TicketId.of(UUID.randomUUID()), TicketStatus.ESCALATED, TicketStatus.IN_PROGRESS, null, null, null,
            UUID.randomUUID(), EscalationResumeReasonCode.ESCALATION_NOT_REQUIRED, "Escalation was not necessary after review.",
            "IT_SUPPORT", "lead.sam", Instant.parse("2026-08-08T23:00:00Z"), OwnershipStatus.UNASSIGNED,
            "SM-049", "TICKET_ESCALATION_RESUMED", 1L, Instant.parse("2026-08-08T23:00:00Z")
        );
        OutboxEventEntry entry = mapper.map(unassignedEvent, "trace-1", "corr-1", "cmd-1");

        validator.validate(entry.eventType(), entry.eventVersion(), entry.payload());
    }

    @Test
    void shouldFailSchemaValidationWhenPayloadIsMissingARequiredField() {
        OutboxEventEntry entry = mapper.map(sampleEvent(), "trace-1", "corr-1", "cmd-1");
        HashMap<String, Object> incompletePayload = new HashMap<>(entry.payload());
        incompletePayload.remove("resumeReason");

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
    void shouldFailSchemaValidationForAnInvalidOwnershipStatus() {
        OutboxEventEntry entry = mapper.map(sampleEvent(), "trace-1", "corr-1", "cmd-1");
        HashMap<String, Object> tamperedPayload = new HashMap<>(entry.payload());
        tamperedPayload.put("ownershipStatus", "NOT_A_REAL_STATUS");

        assertThatThrownBy(() -> validator.validate(entry.eventType(), entry.eventVersion(), tamperedPayload))
            .isInstanceOf(EventSchemaValidationException.class);
    }
}
