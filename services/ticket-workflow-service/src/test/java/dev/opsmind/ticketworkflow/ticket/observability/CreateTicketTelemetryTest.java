package dev.opsmind.ticketworkflow.ticket.observability;

import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("observability")
class CreateTicketTelemetryTest {

    @Test
    void shouldIncrementCreatedCounterWithLowCardinalityTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TicketTelemetry telemetry = new TicketTelemetry(registry);

        telemetry.recordCreated(ApplicationCode.HOUSING_PORTAL, TicketSource.PORTAL);

        double count = registry.get("opsmind_ticket_created_total")
            .tag("application_code", "HOUSING_PORTAL")
            .tag("source", "PORTAL")
            .counter()
            .count();
        assertThat(count).isEqualTo(1.0);
    }

    @Test
    void shouldIncrementIdempotencyReplayCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TicketTelemetry telemetry = new TicketTelemetry(registry);

        telemetry.recordIdempotencyReplay();
        telemetry.recordIdempotencyReplay();

        double count = registry.get("opsmind_ticket_idempotency_replay_total").counter().count();
        assertThat(count).isEqualTo(2.0);
    }

    @Test
    void shouldIncrementAuthorizationDeniedCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TicketTelemetry telemetry = new TicketTelemetry(registry);

        telemetry.recordAuthorizationDenied("createTicket");

        double count = registry.get("opsmind_ticket_authorization_denied_total")
            .tag("operation", "createTicket")
            .counter()
            .count();
        assertThat(count).isEqualTo(1.0);
    }
}
