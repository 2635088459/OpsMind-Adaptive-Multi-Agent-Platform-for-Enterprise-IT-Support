package dev.opsmind.ticketworkflow.ticket.application.observability;

import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Central place for Create Ticket metric names so they are not scattered
 * across application code (13-package-and-class-design §59). Labels stay
 * low-cardinality: no ticketId, requesterId, eventId, or idempotencyKey.
 */
@Component
public class TicketTelemetry {

    private final MeterRegistry meterRegistry;

    public TicketTelemetry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordCreated(ApplicationCode applicationCode, TicketSource source) {
        Counter.builder("opsmind_ticket_created_total")
            .tag("application_code", applicationCode.name())
            .tag("source", source.name())
            .register(meterRegistry)
            .increment();
    }

    public void recordIdempotencyReplay() {
        Counter.builder("opsmind_ticket_idempotency_replay_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordAuthorizationDenied(String operation) {
        Counter.builder("opsmind_ticket_authorization_denied_total")
            .tag("operation", operation)
            .register(meterRegistry)
            .increment();
    }
}
