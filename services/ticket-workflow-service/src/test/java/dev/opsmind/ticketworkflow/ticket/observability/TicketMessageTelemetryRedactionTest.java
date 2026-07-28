package dev.opsmind.ticketworkflow.ticket.observability;

import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.domain.message.MessageVisibility;
import dev.opsmind.ticketworkflow.ticket.domain.message.TicketMessageType;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-TW-004 §18: Add Ticket Message metrics stay low-cardinality.
 * TicketId, MessageId, AuthorId, and the Idempotency Key are forbidden as
 * Prometheus labels; only bounded actor/message-type/visibility
 * dimensions are allowed.
 */
@Tag("unit")
class TicketMessageTelemetryRedactionTest {

    @Test
    void recordMessageAddShouldOnlyCarryBoundedTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TicketTelemetry telemetry = new TicketTelemetry(registry);
        String suspiciousId = UUID.randomUUID().toString();

        telemetry.recordMessageAdd("EMPLOYEE", TicketMessageType.PUBLIC_REQUESTER_MESSAGE, MessageVisibility.PUBLIC);

        Meter meter = registry.find("opsmind_ticket_message_add_total").meter();
        assertThat(meter).isNotNull();
        assertThat(meter.getId().getTags()).hasSize(3);
        for (Meter.Id id : registry.getMeters().stream().map(Meter::getId).toList()) {
            id.getTags().forEach(tag -> {
                assertThat(tag.getKey()).isNotIn("ticketId", "messageId", "authorId", "idempotencyKey");
                assertThat(tag.getValue()).isNotEqualTo(suspiciousId);
            });
        }
    }

    @Test
    void otherMessageCountersShouldCarryNoIdentifyingTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TicketTelemetry telemetry = new TicketTelemetry(registry);

        telemetry.recordMessageReplay();
        telemetry.recordMessageStateRejected();
        telemetry.recordMessageAuthorizationDenied();
        telemetry.recordMessageSecretRejected();

        assertThat(registry.find("opsmind_ticket_message_replay_total").counter().getId().getTags()).isEmpty();
        assertThat(registry.find("opsmind_ticket_message_state_rejected_total").counter().getId().getTags()).isEmpty();
        assertThat(registry.find("opsmind_ticket_message_authorization_denied_total").counter().getId().getTags()).isEmpty();
        assertThat(registry.find("opsmind_ticket_message_secret_rejected_total").counter().getId().getTags()).isEmpty();
    }
}
