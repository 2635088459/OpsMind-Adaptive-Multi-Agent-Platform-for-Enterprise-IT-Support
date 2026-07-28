package dev.opsmind.ticketworkflow.ticket.observability;

import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-TW-005 §22: Support Queue metrics stay low-cardinality. Team ids,
 * agent ids, Ticket ids, RequesterRefs, cursors, and scope fingerprints
 * are forbidden labels; only bounded {@code cursor_present} is allowed.
 */
@Tag("unit")
class SupportQueueTelemetryRedactionTest {

    @Test
    void recordSupportQueueListShouldOnlyCarryBoundedBooleanTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TicketTelemetry telemetry = new TicketTelemetry(registry);
        String suspiciousScopeFingerprint = "sha256:" + UUID.randomUUID();

        telemetry.recordSupportQueueList(true, 20);

        Meter meter = registry.find("opsmind_support_queue_list_total").meter();
        assertThat(meter).isNotNull();
        assertThat(meter.getId().getTags()).hasSize(1);
        for (Meter.Id id : registry.getMeters().stream().map(Meter::getId).toList()) {
            id.getTags().forEach(tag -> {
                assertThat(tag.getKey()).isNotEqualTo("teamId");
                assertThat(tag.getKey()).isNotEqualTo("agentId");
                assertThat(tag.getKey()).isNotEqualTo("ticketId");
                assertThat(tag.getKey()).isNotEqualTo("requesterRef");
                assertThat(tag.getKey()).isNotEqualTo("cursor");
                assertThat(tag.getKey()).isNotEqualTo("scopeFingerprint");
                assertThat(tag.getValue()).isNotEqualTo(suspiciousScopeFingerprint);
            });
        }
    }

    @Test
    void invalidCursorAndAuthorizationCountersShouldCarryNoTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TicketTelemetry telemetry = new TicketTelemetry(registry);

        telemetry.recordSupportQueueInvalidCursor();
        telemetry.recordSupportQueueAuthorizationDenied();
        telemetry.recordSupportQueueFilterOutsideScope();

        assertThat(registry.find("opsmind_support_queue_invalid_cursor_total").counter().getId().getTags()).isEmpty();
        assertThat(registry.find("opsmind_support_queue_authorization_denied_total").counter().getId().getTags()).isEmpty();
        assertThat(registry.find("opsmind_support_queue_filter_outside_scope_total").counter().getId().getTags()).isEmpty();
    }

    @Test
    void resultCountShouldBeRecordedAsADistributionNotAHighCardinalityLabel() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TicketTelemetry telemetry = new TicketTelemetry(registry);

        telemetry.recordSupportQueueList(false, 25);

        assertThat(registry.find("opsmind_support_queue_result_count").summary().count()).isEqualTo(1);
        assertThat(registry.find("opsmind_support_queue_result_count").summary().totalAmount()).isEqualTo(25.0);
    }
}
