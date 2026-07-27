package dev.opsmind.ticketworkflow.ticket.observability;

import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-TW-003 §17: List Requester Tickets metrics stay low-cardinality.
 * RequesterId, TicketId, and the raw cursor are forbidden as Prometheus
 * labels; only bounded {@code cursor_present} / {@code has_filters}
 * dimensions are allowed.
 */
@Tag("unit")
class ListRequesterTicketsTelemetryRedactionTest {

    @Test
    void recordListShouldOnlyCarryBoundedBooleanTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TicketTelemetry telemetry = new TicketTelemetry(registry);
        String suspiciousCursor = "eyJsYXN0VGlja2V0SWQiOiI" + UUID.randomUUID();

        telemetry.recordList(true, true, 20);

        Meter meter = registry.find("opsmind_ticket_list_total").meter();
        assertThat(meter).isNotNull();
        assertThat(meter.getId().getTags()).hasSize(2);
        for (Meter.Id id : registry.getMeters().stream().map(Meter::getId).toList()) {
            id.getTags().forEach(tag -> {
                assertThat(tag.getKey()).isNotEqualTo("requesterId");
                assertThat(tag.getKey()).isNotEqualTo("ticketId");
                assertThat(tag.getKey()).isNotEqualTo("cursor");
                assertThat(tag.getValue()).isNotEqualTo(suspiciousCursor);
            });
        }
    }

    @Test
    void invalidCursorAndAuthorizationDeniedCountersShouldCarryNoTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TicketTelemetry telemetry = new TicketTelemetry(registry);

        telemetry.recordListInvalidCursor();
        telemetry.recordListAuthorizationDenied();

        assertThat(registry.find("opsmind_ticket_list_invalid_cursor_total").counter().getId().getTags()).isEmpty();
        assertThat(registry.find("opsmind_ticket_list_authorization_denied_total").counter().getId().getTags()).isEmpty();
    }

    @Test
    void resultCountShouldBeRecordedAsADistributionNotAHighCardinalityLabel() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TicketTelemetry telemetry = new TicketTelemetry(registry);

        telemetry.recordList(false, false, 20);

        assertThat(registry.find("opsmind_ticket_list_result_count").summary().count()).isEqualTo(1);
        assertThat(registry.find("opsmind_ticket_list_result_count").summary().totalAmount()).isEqualTo(20.0);
    }
}
