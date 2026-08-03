package dev.opsmind.ticketworkflow.ticket.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketStatusTransitionEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.EventSchemaValidationException;
import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketStatusChanged;
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

@Tag("contract")
class TicketStatusChangedEventContractTest {

    private final TicketStatusTransitionEventMapper mapper = new TicketStatusTransitionEventMapper();
    private final JsonSchemaEventValidator validator = new JsonSchemaEventValidator(new ObjectMapper());

    @Test
    void shouldProduceEnvelopeFieldsAndPassSchemaValidationForStartWork() {
        OutboxEventEntry entry = mapper.map(sampleStartWorkEvent(), SupportQueueId.of(UUID.randomUUID()), "trace-1", "corr-1", "cmd-1");

        assertThat(entry.eventType()).isEqualTo("ticket.status.changed");
        assertThat(entry.eventVersion()).isEqualTo("1.0");
        assertThat(entry.routingKey()).isEqualTo("ticket.status-changed.v1");
        assertThat(entry.aggregateType()).isEqualTo("Ticket");
        assertThat(entry.aggregateVersion()).isEqualTo(14L);
        assertThat(entry.payload()).containsEntry("previousStatus", "ASSIGNED");
        assertThat(entry.payload()).containsEntry("newStatus", "IN_PROGRESS");
        assertThat(entry.payload()).containsEntry("transitionId", "SM-005");
        assertThat(entry.payload()).containsEntry("reasonCode", "WORK_STARTED");

        validator.validate(entry.eventType(), entry.eventVersion(), entry.payload());
    }

    @Test
    void shouldPassSchemaValidationForWaitingForUserMetadata() {
        TicketStatusChanged event = new TicketStatusChanged(
            TicketId.of(UUID.randomUUID()), TicketStatus.IN_PROGRESS, TicketStatus.WAITING_FOR_USER,
            "agent-1", "IT_SUPPORT", "support-100", "Requester must provide device serial number",
            "SM-006", "WAITING_FOR_USER", Instant.parse("2026-07-31T18:35:00Z"), null,
            15L, Instant.parse("2026-07-31T18:35:00Z")
        );
        OutboxEventEntry entry = mapper.map(event, SupportQueueId.of(UUID.randomUUID()), "trace-1", "corr-1", "cmd-1");

        validator.validate(entry.eventType(), entry.eventVersion(), entry.payload());
    }

    @Test
    void shouldFailSchemaValidationWhenPayloadHasMismatchedWaitingMetadata() {
        OutboxEventEntry entry = mapper.map(sampleStartWorkEvent(), SupportQueueId.of(UUID.randomUUID()), "trace-1", "corr-1", "cmd-1");
        HashMap<String, Object> invalidPayload = new HashMap<>(entry.payload());
        invalidPayload.put("waitingForRequesterSince", "2026-07-31T18:35:00Z");

        assertThatThrownBy(() -> validator.validate(entry.eventType(), entry.eventVersion(), invalidPayload))
            .isInstanceOf(EventSchemaValidationException.class);
    }

    private TicketStatusChanged sampleStartWorkEvent() {
        return new TicketStatusChanged(
            TicketId.of(UUID.randomUUID()), TicketStatus.ASSIGNED, TicketStatus.IN_PROGRESS,
            "agent-1", "IT_SUPPORT", "support-100", "Starting endpoint investigation",
            "SM-005", "WORK_STARTED", null, null, 14L, Instant.parse("2026-07-31T18:35:00Z")
        );
    }
}
