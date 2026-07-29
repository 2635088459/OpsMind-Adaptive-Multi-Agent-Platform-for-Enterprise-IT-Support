package dev.opsmind.ticketworkflow.ticket.observability;

import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineViewType;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-TW-006 §25: Timeline metrics stay low-cardinality — {@code
 * view_type} and {@code cursor_present} are the only allowed labels on the
 * request counter, and Ticket IDs, item IDs, actor IDs, requester IDs, and
 * raw cursors are forbidden metric labels or values anywhere in the
 * registry.
 */
@Tag("unit")
class TicketTimelineTelemetryRedactionTest {

    @Test
    void recordTimelineShouldOnlyTagByViewTypeAndCursorPresence() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TicketTelemetry telemetry = new TicketTelemetry(registry);
        String suspiciousTicketId = UUID.randomUUID().toString();
        String suspiciousItemId = "MESSAGE:" + UUID.randomUUID();
        String suspiciousCursor = "eyJhbGciOiJIUzI1NiJ9.fake-cursor-payload.signature";

        telemetry.recordTimeline(TicketTimelineViewType.SUPPORT_INTERNAL_VIEW, true, 12);

        Meter meter = registry.find("opsmind_ticket_timeline_total").meter();
        assertThat(meter).isNotNull();
        assertThat(meter.getId().getTags()).hasSize(2);
        assertThat(meter.getId().getTag("view_type")).isEqualTo("SUPPORT_INTERNAL_VIEW");
        assertThat(meter.getId().getTag("cursor_present")).isEqualTo("true");

        for (Meter.Id id : registry.getMeters().stream().map(Meter::getId).toList()) {
            id.getTags().forEach(tag -> {
                assertThat(tag.getKey()).isNotEqualTo("ticketId");
                assertThat(tag.getKey()).isNotEqualTo("itemId");
                assertThat(tag.getKey()).isNotEqualTo("actorId");
                assertThat(tag.getKey()).isNotEqualTo("requesterId");
                assertThat(tag.getKey()).isNotEqualTo("cursor");
                assertThat(tag.getValue()).isNotEqualTo(suspiciousTicketId);
                assertThat(tag.getValue()).isNotEqualTo(suspiciousItemId);
                assertThat(tag.getValue()).isNotEqualTo(suspiciousCursor);
            });
        }
    }

    @Test
    void notFoundAuthorizationDeniedInvalidCursorAndAuditFailureCountersShouldCarryNoTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TicketTelemetry telemetry = new TicketTelemetry(registry);

        telemetry.recordTimelineNotFound();
        telemetry.recordTimelineAuthorizationDenied();
        telemetry.recordTimelineInvalidCursor();
        telemetry.recordTimelineSensitiveReadAuditFailure();

        assertThat(registry.find("opsmind_ticket_timeline_not_found_total").counter().getId().getTags()).isEmpty();
        assertThat(registry.find("opsmind_ticket_timeline_authorization_denied_total").counter().getId().getTags()).isEmpty();
        assertThat(registry.find("opsmind_ticket_timeline_invalid_cursor_total").counter().getId().getTags()).isEmpty();
        assertThat(registry.find("opsmind_ticket_timeline_sensitive_read_audit_failure_total").counter().getId().getTags()).isEmpty();
    }

    @Test
    void resultCountShouldBeRecordedAsADistributionNotAHighCardinalityLabel() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TicketTelemetry telemetry = new TicketTelemetry(registry);

        telemetry.recordTimeline(TicketTimelineViewType.EMPLOYEE_PUBLIC_VIEW, false, 7);

        assertThat(registry.find("opsmind_ticket_timeline_result_count").summary().count()).isEqualTo(1);
        assertThat(registry.find("opsmind_ticket_timeline_result_count").summary().totalAmount()).isEqualTo(7.0);
    }

    @Test
    void timerShouldNotCarryAnyIdentifyingTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TicketTelemetry telemetry = new TicketTelemetry(registry);

        var sample = telemetry.startTimelineTimer();
        telemetry.stopTimelineTimer(sample);

        assertThat(registry.find("opsmind_ticket_timeline_duration_seconds").timer().getId().getTags()).isEmpty();
    }
}
