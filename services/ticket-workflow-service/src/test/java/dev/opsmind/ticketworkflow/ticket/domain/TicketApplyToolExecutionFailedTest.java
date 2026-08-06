package dev.opsmind.ticketworkflow.ticket.domain;

import dev.opsmind.ticketworkflow.ticket.domain.event.TicketToolExecutionFailedApplied;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidStatusTransitionException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.TicketNotAssignedException;
import dev.opsmind.ticketworkflow.ticket.domain.model.Ticket;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SPEC-TW-020 domain-rules §1: {@code EXECUTING -> IN_PROGRESS}/{@code FAILED} and its invariants. */
@Tag("unit")
class TicketApplyToolExecutionFailedTest {

    private static final TicketId TICKET_ID = TicketId.of(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-08-05T18:00:00Z");
    private static final Instant FAILED_AT = Instant.parse("2026-08-05T17:55:00Z");
    private static final String ASSIGNEE_ID = "sam.support";

    private TicketToolExecutionFailedApplied apply(String failureClass) {
        return Ticket.applyToolExecutionFailed(
            TICKET_ID, TicketStatus.EXECUTING, ASSIGNEE_ID, 40L, "wf-9000", "act-100", "auth-5678",
            "exec-500", "TARGET_ACCOUNT_NOT_FOUND", failureClass, FAILED_AT, false, "evt-failed-1", NOW
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"KNOWN_SAFE", "RETRYABLE_SAFE"})
    void shouldReturnTheTicketToInProgressOnASafeFailure(String failureClass) {
        TicketToolExecutionFailedApplied event = apply(failureClass);

        assertThat(event.previousStatus()).isEqualTo(TicketStatus.EXECUTING);
        assertThat(event.newStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(event.transitionId()).isEqualTo("SM-022");
        assertThat(event.reasonCode()).isEqualTo("TOOL_EXECUTION_FAILED_SAFE");
        assertThat(event.failureClass()).isEqualTo(failureClass);
        assertThat(event.aggregateVersion()).isEqualTo(41L);
    }

    @Test
    void shouldMoveTheTicketToFailedOnAPipelineFailure() {
        TicketToolExecutionFailedApplied event = apply("PIPELINE_FAILED");

        assertThat(event.previousStatus()).isEqualTo(TicketStatus.EXECUTING);
        assertThat(event.newStatus()).isEqualTo(TicketStatus.FAILED);
        assertThat(event.transitionId()).isEqualTo("SM-023");
        assertThat(event.reasonCode()).isEqualTo("TOOL_EXECUTION_PIPELINE_FAILED");
        assertThat(event.workflowId()).isEqualTo("wf-9000");
        assertThat(event.actionId()).isEqualTo("act-100");
        assertThat(event.authorizationReference()).isEqualTo("auth-5678");
        assertThat(event.toolExecutionId()).isEqualTo("exec-500");
        assertThat(event.failureCode()).isEqualTo("TARGET_ACCOUNT_NOT_FOUND");
        assertThat(event.failedAt()).isEqualTo(FAILED_AT);
        assertThat(event.safeToRetry()).isFalse();
        assertThat(event.failedEventId()).isEqualTo("evt-failed-1");
    }

    @Test
    void shouldRejectAnUnknownSideEffectFailureClass() {
        assertThatThrownBy(() -> apply("UNKNOWN_SIDE_EFFECT")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectAnUnrecognizedFailureClass() {
        assertThatThrownBy(() -> apply("SOMETHING_ELSE")).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {"EXECUTING"}, mode = EnumSource.Mode.EXCLUDE)
    void shouldRejectEveryStatusOtherThanExecutingForASafeFailure(TicketStatus currentStatus) {
        assertThatThrownBy(() -> Ticket.applyToolExecutionFailed(
            TICKET_ID, currentStatus, ASSIGNEE_ID, 40L, "wf-9000", "act-100", "auth-5678",
            "exec-500", "TARGET_ACCOUNT_NOT_FOUND", "KNOWN_SAFE", FAILED_AT, false, "evt-failed-1", NOW
        ))
            .isInstanceOfSatisfying(InvalidStatusTransitionException.class, ex -> {
                assertThat(ex.currentStatus()).isEqualTo(currentStatus);
                assertThat(ex.targetStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
            });
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {"EXECUTING"}, mode = EnumSource.Mode.EXCLUDE)
    void shouldRejectEveryStatusOtherThanExecutingForAPipelineFailure(TicketStatus currentStatus) {
        assertThatThrownBy(() -> Ticket.applyToolExecutionFailed(
            TICKET_ID, currentStatus, ASSIGNEE_ID, 40L, "wf-9000", "act-100", "auth-5678",
            "exec-500", "TARGET_ACCOUNT_NOT_FOUND", "PIPELINE_FAILED", FAILED_AT, false, "evt-failed-1", NOW
        ))
            .isInstanceOfSatisfying(InvalidStatusTransitionException.class, ex -> {
                assertThat(ex.currentStatus()).isEqualTo(currentStatus);
                assertThat(ex.targetStatus()).isEqualTo(TicketStatus.FAILED);
            });
    }

    @Test
    void shouldRejectAMissingAssignee() {
        assertThatThrownBy(() -> Ticket.applyToolExecutionFailed(
            TICKET_ID, TicketStatus.EXECUTING, null, 40L, "wf-9000", "act-100", "auth-5678",
            "exec-500", "TARGET_ACCOUNT_NOT_FOUND", "KNOWN_SAFE", FAILED_AT, false, "evt-failed-1", NOW
        )).isInstanceOf(TicketNotAssignedException.class);
    }

    @Test
    void shouldAllowANullSafeToRetry() {
        TicketToolExecutionFailedApplied event = Ticket.applyToolExecutionFailed(
            TICKET_ID, TicketStatus.EXECUTING, ASSIGNEE_ID, 40L, "wf-9000", "act-100", "auth-5678",
            "exec-500", "TARGET_ACCOUNT_NOT_FOUND", "KNOWN_SAFE", FAILED_AT, null, "evt-failed-1", NOW
        );

        assertThat(event.safeToRetry()).isNull();
    }
}
