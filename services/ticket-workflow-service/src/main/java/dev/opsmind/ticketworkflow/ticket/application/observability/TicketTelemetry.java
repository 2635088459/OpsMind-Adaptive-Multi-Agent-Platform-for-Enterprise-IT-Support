package dev.opsmind.ticketworkflow.ticket.application.observability;

import dev.opsmind.ticketworkflow.ticket.application.query.TicketViewType;
import dev.opsmind.ticketworkflow.ticket.domain.message.MessageVisibility;
import dev.opsmind.ticketworkflow.ticket.domain.message.TicketMessageType;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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

    public Timer.Sample startGetTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopGetTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("opsmind_ticket_get_duration_seconds").register(meterRegistry));
    }

    public void recordGet(TicketViewType viewType) {
        Counter.builder("opsmind_ticket_get_total")
            .tag("view_type", viewType.name())
            .register(meterRegistry)
            .increment();
    }

    public void recordGetNotFound() {
        Counter.builder("opsmind_ticket_get_not_found_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordGetAuthorizationDenied() {
        Counter.builder("opsmind_ticket_get_authorization_denied_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordGetNotModified() {
        Counter.builder("opsmind_ticket_get_not_modified_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordSensitiveReadAuditFailure() {
        Counter.builder("opsmind_ticket_sensitive_read_audit_failure_total")
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startListTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopListTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("opsmind_ticket_list_duration_seconds").register(meterRegistry));
    }

    public void recordList(boolean cursorPresent, boolean hasFilters, int resultCount) {
        Counter.builder("opsmind_ticket_list_total")
            .tag("cursor_present", String.valueOf(cursorPresent))
            .tag("has_filters", String.valueOf(hasFilters))
            .register(meterRegistry)
            .increment();
        DistributionSummary.builder("opsmind_ticket_list_result_count")
            .register(meterRegistry)
            .record(resultCount);
    }

    public void recordListInvalidCursor() {
        Counter.builder("opsmind_ticket_list_invalid_cursor_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordListAuthorizationDenied() {
        Counter.builder("opsmind_ticket_list_authorization_denied_total")
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startMessageAddTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopMessageAddTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("opsmind_ticket_message_add_duration_seconds").register(meterRegistry));
    }

    public void recordMessageAdd(String actorType, TicketMessageType messageType, MessageVisibility visibility) {
        Counter.builder("opsmind_ticket_message_add_total")
            .tag("actor_type", actorType)
            .tag("message_type", messageType.name())
            .tag("visibility", visibility.name())
            .register(meterRegistry)
            .increment();
    }

    public void recordMessageReplay() {
        Counter.builder("opsmind_ticket_message_replay_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordMessageStateRejected() {
        Counter.builder("opsmind_ticket_message_state_rejected_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordMessageAuthorizationDenied() {
        Counter.builder("opsmind_ticket_message_authorization_denied_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordMessageSecretRejected() {
        Counter.builder("opsmind_ticket_message_secret_rejected_total")
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startSupportQueueTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopSupportQueueTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("opsmind_support_queue_duration_seconds").register(meterRegistry));
    }

    public void recordSupportQueueList(boolean cursorPresent, int resultCount) {
        Counter.builder("opsmind_support_queue_list_total")
            .tag("cursor_present", String.valueOf(cursorPresent))
            .register(meterRegistry)
            .increment();
        DistributionSummary.builder("opsmind_support_queue_result_count")
            .register(meterRegistry)
            .record(resultCount);
    }

    public void recordSupportQueueInvalidCursor() {
        Counter.builder("opsmind_support_queue_invalid_cursor_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordSupportQueueAuthorizationDenied() {
        Counter.builder("opsmind_support_queue_authorization_denied_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordSupportQueueFilterOutsideScope() {
        Counter.builder("opsmind_support_queue_filter_outside_scope_total")
            .register(meterRegistry)
            .increment();
    }
}
