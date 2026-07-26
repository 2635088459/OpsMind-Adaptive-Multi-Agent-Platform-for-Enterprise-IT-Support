package dev.opsmind.ticketworkflow.ticket.observability;

import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketViewType;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-TW-002 §19: Get Ticket metrics stay low-cardinality. Ticket ID and
 * requester ID are forbidden as Prometheus labels; only bounded actor/view
 * dimensions are allowed.
 */
@Tag("unit")
class GetTicketTelemetryRedactionTest {

    @Test
    void recordGetShouldOnlyTagByViewType() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TicketTelemetry telemetry = new TicketTelemetry(registry);
        String suspiciousTicketId = UUID.randomUUID().toString();

        telemetry.recordGet(TicketViewType.SUPPORT_VIEW);

        Meter meter = registry.find("opsmind_ticket_get_total").meter();
        assertThat(meter).isNotNull();
        for (Meter.Id id : registry.getMeters().stream().map(Meter::getId).toList()) {
            id.getTags().forEach(tag -> {
                assertThat(tag.getKey()).isNotEqualTo("ticketId");
                assertThat(tag.getKey()).isNotEqualTo("requesterId");
                assertThat(tag.getValue()).isNotEqualTo(suspiciousTicketId);
            });
        }
        assertThat(meter.getId().getTags()).hasSize(1);
        assertThat(meter.getId().getTag("view_type")).isEqualTo("SUPPORT_VIEW");
    }

    @Test
    void notFoundAndAuthorizationDeniedCountersShouldCarryNoIdentifyingTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TicketTelemetry telemetry = new TicketTelemetry(registry);

        telemetry.recordGetNotFound();
        telemetry.recordGetAuthorizationDenied();
        telemetry.recordGetNotModified();
        telemetry.recordSensitiveReadAuditFailure();

        assertThat(registry.find("opsmind_ticket_get_not_found_total").counter().getId().getTags()).isEmpty();
        assertThat(registry.find("opsmind_ticket_get_authorization_denied_total").counter().getId().getTags()).isEmpty();
        assertThat(registry.find("opsmind_ticket_get_not_modified_total").counter().getId().getTags()).isEmpty();
        assertThat(registry.find("opsmind_ticket_sensitive_read_audit_failure_total").counter().getId().getTags()).isEmpty();
    }
}
