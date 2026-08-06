package dev.opsmind.ticketworkflow.ticket.domain;

import dev.opsmind.ticketworkflow.ticket.domain.event.TicketVerificationFailureApplied;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidStatusTransitionException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.TicketNotAssignedException;
import dev.opsmind.ticketworkflow.ticket.domain.model.Ticket;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SPEC-TW-024 domain-rules §1: {@code VERIFYING -> IN_PROGRESS}/{@code ESCALATED}/{@code FAILED} and its invariants. */
@Tag("unit")
class TicketApplyVerificationFailureTest {

    private static final TicketId TICKET_ID = TicketId.of(UUID.randomUUID());
    private static final UUID RESOLUTION_CYCLE_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-09T18:00:00Z");
    private static final Instant FAILED_AT = Instant.parse("2026-08-09T17:55:00Z");
    private static final String ASSIGNEE_ID = "sam.support";

    private TicketVerificationFailureApplied apply(String failureClass, boolean unsafeResult, boolean hasReachedFailureLimit) {
        return Ticket.applyVerificationFailure(
            TICKET_ID, TicketStatus.VERIFYING, ASSIGNEE_ID, 62L, "ver-1234", "wf-9000", RESOLUTION_CYCLE_ID, 1,
            "LOGIN_STILL_FAILS", failureClass, unsafeResult, hasReachedFailureLimit, FAILED_AT, "evt-verification-failed-1", NOW
        );
    }

    @Test
    void shouldReturnTheTicketToInProgressOnARetryableFailureUnderTheLimit() {
        TicketVerificationFailureApplied event = apply("RETRYABLE", false, false);

        assertThat(event.previousStatus()).isEqualTo(TicketStatus.VERIFYING);
        assertThat(event.newStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(event.transitionId()).isEqualTo("SM-027");
        assertThat(event.reasonCode()).isEqualTo("VERIFICATION_FAILED_RETRYABLE");
        assertThat(event.failureClass()).isEqualTo("RETRYABLE");
        assertThat(event.unsafeResult()).isFalse();
        assertThat(event.aggregateVersion()).isEqualTo(63L);
    }

    @Test
    void shouldEscalateOnARetryableFailureThatHasReachedTheLimit() {
        TicketVerificationFailureApplied event = apply("RETRYABLE", false, true);

        assertThat(event.newStatus()).isEqualTo(TicketStatus.ESCALATED);
        assertThat(event.transitionId()).isEqualTo("SM-028");
        assertThat(event.reasonCode()).isEqualTo("VERIFICATION_FAILED_LIMIT_OR_UNSAFE");
    }

    @Test
    void shouldEscalateOnAnUnsafeResultEvenWhenRetryableAndUnderTheLimit() {
        TicketVerificationFailureApplied event = apply("RETRYABLE", true, false);

        assertThat(event.newStatus()).isEqualTo(TicketStatus.ESCALATED);
        assertThat(event.transitionId()).isEqualTo("SM-028");
        assertThat(event.reasonCode()).isEqualTo("VERIFICATION_FAILED_LIMIT_OR_UNSAFE");
        assertThat(event.unsafeResult()).isTrue();
    }

    @Test
    void shouldEscalateOnAnUnsafePipelineFailureToo() {
        TicketVerificationFailureApplied event = apply("PIPELINE_FAILED", true, false);

        assertThat(event.newStatus()).isEqualTo(TicketStatus.ESCALATED);
        assertThat(event.transitionId()).isEqualTo("SM-028");
    }

    @Test
    void shouldMoveTheTicketToFailedOnAPipelineFailure() {
        TicketVerificationFailureApplied event = apply("PIPELINE_FAILED", false, false);

        assertThat(event.newStatus()).isEqualTo(TicketStatus.FAILED);
        assertThat(event.transitionId()).isEqualTo("SM-029");
        assertThat(event.reasonCode()).isEqualTo("VERIFICATION_PIPELINE_FAILED");
        assertThat(event.workflowId()).isEqualTo("wf-9000");
        assertThat(event.resolutionCycleId()).isEqualTo(RESOLUTION_CYCLE_ID);
        assertThat(event.attemptNumber()).isEqualTo(1);
        assertThat(event.failureCode()).isEqualTo("LOGIN_STILL_FAILS");
        assertThat(event.failedAt()).isEqualTo(FAILED_AT);
        assertThat(event.failedEventId()).isEqualTo("evt-verification-failed-1");
    }

    @Test
    void shouldRejectAnUnrecognizedFailureClass() {
        assertThatThrownBy(() -> apply("SOMETHING_ELSE", false, false)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {"VERIFYING"}, mode = EnumSource.Mode.EXCLUDE)
    void shouldRejectEveryStatusOtherThanVerifyingForARetryableFailure(TicketStatus currentStatus) {
        assertThatThrownBy(() -> Ticket.applyVerificationFailure(
            TICKET_ID, currentStatus, ASSIGNEE_ID, 62L, "ver-1234", "wf-9000", RESOLUTION_CYCLE_ID, 1,
            "LOGIN_STILL_FAILS", "RETRYABLE", false, false, FAILED_AT, "evt-verification-failed-1", NOW
        ))
            .isInstanceOfSatisfying(InvalidStatusTransitionException.class, ex -> {
                assertThat(ex.currentStatus()).isEqualTo(currentStatus);
                assertThat(ex.targetStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
            });
    }

    @Test
    void shouldRejectAMissingAssignee() {
        assertThatThrownBy(() -> Ticket.applyVerificationFailure(
            TICKET_ID, TicketStatus.VERIFYING, null, 62L, "ver-1234", "wf-9000", RESOLUTION_CYCLE_ID, 1,
            "LOGIN_STILL_FAILS", "RETRYABLE", false, false, FAILED_AT, "evt-verification-failed-1", NOW
        )).isInstanceOf(TicketNotAssignedException.class);
    }
}
