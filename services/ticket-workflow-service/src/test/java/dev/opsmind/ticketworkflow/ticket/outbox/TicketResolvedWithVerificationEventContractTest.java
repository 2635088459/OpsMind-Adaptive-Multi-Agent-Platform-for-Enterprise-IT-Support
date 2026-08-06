package dev.opsmind.ticketworkflow.ticket.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketResolvedWithVerificationEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.EventSchemaValidationException;
import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketResolvedWithVerification;
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

/** SPEC-TW-025 event-contract §3: mirrors {@code TicketResolvedEventContractTest}'s shape. */
@Tag("contract")
class TicketResolvedWithVerificationEventContractTest {

    private final TicketResolvedWithVerificationEventMapper mapper = new TicketResolvedWithVerificationEventMapper();
    private final JsonSchemaEventValidator validator = new JsonSchemaEventValidator(new ObjectMapper());

    private TicketResolvedWithVerification sampleEvent() {
        return new TicketResolvedWithVerification(
            TicketId.of(UUID.randomUUID()),
            TicketStatus.VERIFYING,
            TicketStatus.RESOLVED,
            "sam.support",
            UUID.randomUUID(),
            "ver-1234",
            "ve-300",
            ResolutionCode.FIXED,
            "Verification confirmed the requester can sign in after MFA reset.",
            "SERVICE",
            "verification-orchestrator",
            Instant.parse("2026-08-06T19:05:00Z"),
            Instant.parse("2026-08-13T19:05:00Z"),
            "SM-030",
            "VERIFIED_RESOLUTION",
            18L,
            Instant.parse("2026-08-06T19:05:00Z")
        );
    }

    private SupportQueueId sampleQueueId() {
        return SupportQueueId.of(UUID.randomUUID());
    }

    @Test
    void shouldProduceEnvelopeFieldsMatchingApprovedEventIdentity() {
        OutboxEventEntry entry = mapper.map(sampleEvent(), sampleQueueId(), "trace-1", "corr-1", "cmd-1");

        assertThat(entry.eventType()).isEqualTo("ticket.resolved-with-verification");
        assertThat(entry.eventVersion()).isEqualTo("1.0");
        assertThat(entry.routingKey()).isEqualTo("ticket.resolved-with-verification.v1");
        assertThat(entry.aggregateType()).isEqualTo("Ticket");
        assertThat(entry.aggregateVersion()).isEqualTo(18L);
        assertThat(entry.dataClassification()).isEqualTo("INTERNAL");
        assertThat(entry.payload()).containsKeys(
            "supportQueueId", "assigneeId", "resolutionCycleId", "verificationId", "verificationEvidenceId",
            "previousStatus", "newStatus", "resolutionCode", "resolutionSummary", "resolvedBy", "resolvedAt", "autoCloseDueAt"
        );
        assertThat(entry.payload().get("previousStatus")).isEqualTo("VERIFYING");
        assertThat(entry.payload().get("newStatus")).isEqualTo("RESOLVED");
        assertThat(entry.payload().get("verificationId")).isEqualTo("ver-1234");
        assertThat(entry.payload().get("verificationEvidenceId")).isEqualTo("ve-300");
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
        incompletePayload.remove("verificationEvidenceId");

        assertThatThrownBy(() -> validator.validate(entry.eventType(), entry.eventVersion(), incompletePayload))
            .isInstanceOf(EventSchemaValidationException.class);
    }

    @Test
    void shouldFailSchemaValidationWhenPayloadContainsAnAdditionalField() {
        OutboxEventEntry entry = mapper.map(sampleEvent(), sampleQueueId(), "trace-1", "corr-1", "cmd-1");
        HashMap<String, Object> extendedPayload = new HashMap<>(entry.payload());
        extendedPayload.put("resolvedByType", "SERVICE");

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

    @Test
    void shouldFailSchemaValidationForAWrongPreviousStatusValue() {
        OutboxEventEntry entry = mapper.map(sampleEvent(), sampleQueueId(), "trace-1", "corr-1", "cmd-1");
        HashMap<String, Object> tamperedPayload = new HashMap<>(entry.payload());
        tamperedPayload.put("previousStatus", "IN_PROGRESS");

        assertThatThrownBy(() -> validator.validate(entry.eventType(), entry.eventVersion(), tamperedPayload))
            .isInstanceOf(EventSchemaValidationException.class);
    }
}
