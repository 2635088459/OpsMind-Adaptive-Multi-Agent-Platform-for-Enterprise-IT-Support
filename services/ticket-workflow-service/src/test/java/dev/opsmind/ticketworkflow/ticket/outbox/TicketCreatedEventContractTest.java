package dev.opsmind.ticketworkflow.ticket.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.configuration.TicketWorkflowProperties;
import dev.opsmind.ticketworkflow.ticket.application.event.RequesterPseudonymizer;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketIntegrationEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.EventSchemaValidationException;
import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketCreated;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.RequesterId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketSource;
import dev.opsmind.ticketworkflow.ticket.infrastructure.event.JsonSchemaEventValidator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("contract")
class TicketCreatedEventContractTest {

    private final RequesterPseudonymizer pseudonymizer = new RequesterPseudonymizer(
        new TicketWorkflowProperties("contract-test-secret", "unit-test-cursor-secret", new TicketWorkflowProperties.Sla("DEFAULT", Duration.ofHours(4), Duration.ofHours(24), Duration.ofHours(4)))
    );
    private final TicketIntegrationEventMapper mapper = new TicketIntegrationEventMapper(pseudonymizer);
    private final JsonSchemaEventValidator validator = new JsonSchemaEventValidator(new ObjectMapper());

    @Test
    void shouldProduceEnvelopeFieldsMatchingApprovedEventIdentity() {
        TicketCreated event = new TicketCreated(
            TicketId.of(UUID.randomUUID()), TicketDisplayId.of("INC-2048"), RequesterId.of("user-123"),
            ApplicationCode.HOUSING_PORTAL, TicketSource.PORTAL, 0L, Instant.parse("2026-07-23T16:30:00Z")
        );

        OutboxEventEntry entry = mapper.mapTicketCreated(event, "trace-1", "corr-1", "cmd-1");

        assertThat(entry.eventType()).isEqualTo("ticket.created");
        assertThat(entry.eventVersion()).isEqualTo("1.0");
        assertThat(entry.routingKey()).isEqualTo("ticket.created.v1");
        assertThat(entry.aggregateType()).isEqualTo("Ticket");
        assertThat(entry.aggregateVersion()).isZero();
        assertThat(entry.dataClassification()).isEqualTo("INTERNAL");
    }

    @Test
    void shouldPassSchemaValidationForAValidPayload() {
        TicketCreated event = new TicketCreated(
            TicketId.of(UUID.randomUUID()), TicketDisplayId.of("INC-2048"), RequesterId.of("user-123"),
            ApplicationCode.HOUSING_PORTAL, TicketSource.PORTAL, 0L, Instant.parse("2026-07-23T16:30:00Z")
        );
        OutboxEventEntry entry = mapper.mapTicketCreated(event, "trace-1", "corr-1", "cmd-1");

        validator.validate(entry.eventType(), entry.eventVersion(), entry.payload());
    }

    @Test
    void shouldFailSchemaValidationWhenPayloadIsMissingARequiredField() {
        TicketCreated event = new TicketCreated(
            TicketId.of(UUID.randomUUID()), TicketDisplayId.of("INC-2048"), RequesterId.of("user-123"),
            ApplicationCode.HOUSING_PORTAL, TicketSource.PORTAL, 0L, Instant.parse("2026-07-23T16:30:00Z")
        );
        OutboxEventEntry entry = mapper.mapTicketCreated(event, "trace-1", "corr-1", "cmd-1");
        HashMap<String, Object> incompletePayload = new HashMap<>(entry.payload());
        incompletePayload.remove("requesterIdHash");

        assertThatThrownBy(() -> validator.validate(entry.eventType(), entry.eventVersion(), incompletePayload))
            .isInstanceOf(EventSchemaValidationException.class);
    }

    @Test
    void shouldFailSchemaValidationWhenPayloadContainsAnAdditionalField() {
        TicketCreated event = new TicketCreated(
            TicketId.of(UUID.randomUUID()), TicketDisplayId.of("INC-2048"), RequesterId.of("user-123"),
            ApplicationCode.HOUSING_PORTAL, TicketSource.PORTAL, 0L, Instant.parse("2026-07-23T16:30:00Z")
        );
        OutboxEventEntry entry = mapper.mapTicketCreated(event, "trace-1", "corr-1", "cmd-1");
        HashMap<String, Object> extendedPayload = new HashMap<>(entry.payload());
        extendedPayload.put("title", "leaked title");

        assertThatThrownBy(() -> validator.validate(entry.eventType(), entry.eventVersion(), extendedPayload))
            .isInstanceOf(EventSchemaValidationException.class);
    }
}
