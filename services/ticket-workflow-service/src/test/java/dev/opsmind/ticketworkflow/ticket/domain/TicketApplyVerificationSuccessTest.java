package dev.opsmind.ticketworkflow.ticket.domain;

import dev.opsmind.ticketworkflow.ticket.domain.event.TicketVerificationSuccessApplied;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SPEC-TW-023 domain-rules §1: {@code VERIFYING -> VERIFYING} self-transition and its invariants. */
@Tag("unit")
class TicketApplyVerificationSuccessTest {

    private static final TicketId TICKET_ID = TicketId.of(UUID.randomUUID());
    private static final UUID RESOLUTION_CYCLE_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-08T18:00:00Z");
    private static final Instant COMPLETED_AT = Instant.parse("2026-08-08T17:55:00Z");
    private static final String ASSIGNEE_ID = "sam.support";

    private TicketVerificationSuccessApplied apply() {
        return Ticket.applyVerificationSuccess(
            TICKET_ID, TicketStatus.VERIFYING, ASSIGNEE_ID, 61L, "ver-1234", "wf-9000", RESOLUTION_CYCLE_ID, 1,
            "evidence-900", Map.of("checkType", "LOGIN_TEST"), COMPLETED_AT, "evt-verification-1", NOW
        );
    }

    @Test
    void shouldApplyVerificationSuccessOnAVerifyingTicket() {
        TicketVerificationSuccessApplied event = apply();

        assertThat(event.previousStatus()).isEqualTo(TicketStatus.VERIFYING);
        assertThat(event.newStatus()).isEqualTo(TicketStatus.VERIFYING);
        assertThat(event.assigneeId()).isEqualTo(ASSIGNEE_ID);
        assertThat(event.verificationId()).isEqualTo("ver-1234");
        assertThat(event.workflowId()).isEqualTo("wf-9000");
        assertThat(event.resolutionCycleId()).isEqualTo(RESOLUTION_CYCLE_ID);
        assertThat(event.attemptNumber()).isEqualTo(1);
        assertThat(event.verificationEvidenceId()).isEqualTo("evidence-900");
        assertThat(event.evidenceSummary()).containsEntry("checkType", "LOGIN_TEST");
        assertThat(event.completedAt()).isEqualTo(COMPLETED_AT);
        assertThat(event.completedEventId()).isEqualTo("evt-verification-1");
        assertThat(event.transitionId()).isEqualTo("SM-026");
        assertThat(event.reasonCode()).isEqualTo("VERIFICATION_SUCCEEDED");
        assertThat(event.aggregateVersion()).isEqualTo(62L);
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {"VERIFYING"}, mode = EnumSource.Mode.EXCLUDE)
    void shouldRejectEveryStatusOtherThanVerifying(TicketStatus currentStatus) {
        assertThatThrownBy(() -> Ticket.applyVerificationSuccess(
            TICKET_ID, currentStatus, ASSIGNEE_ID, 61L, "ver-1234", "wf-9000", RESOLUTION_CYCLE_ID, 1,
            "evidence-900", null, COMPLETED_AT, "evt-verification-1", NOW
        ))
            .isInstanceOfSatisfying(InvalidStatusTransitionException.class, ex -> {
                assertThat(ex.currentStatus()).isEqualTo(currentStatus);
                assertThat(ex.targetStatus()).isEqualTo(TicketStatus.VERIFYING);
            });
    }

    @Test
    void shouldRejectAMissingAssignee() {
        assertThatThrownBy(() -> Ticket.applyVerificationSuccess(
            TICKET_ID, TicketStatus.VERIFYING, null, 61L, "ver-1234", "wf-9000", RESOLUTION_CYCLE_ID, 1,
            "evidence-900", null, COMPLETED_AT, "evt-verification-1", NOW
        )).isInstanceOf(TicketNotAssignedException.class);
    }

    @Test
    void shouldRejectAnAttemptNumberBelowOne() {
        assertThatThrownBy(() -> Ticket.applyVerificationSuccess(
            TICKET_ID, TicketStatus.VERIFYING, ASSIGNEE_ID, 61L, "ver-1234", "wf-9000", RESOLUTION_CYCLE_ID, 0,
            "evidence-900", null, COMPLETED_AT, "evt-verification-1", NOW
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAllowANullEvidenceSummary() {
        TicketVerificationSuccessApplied event = Ticket.applyVerificationSuccess(
            TICKET_ID, TicketStatus.VERIFYING, ASSIGNEE_ID, 61L, "ver-1234", "wf-9000", RESOLUTION_CYCLE_ID, 1,
            "evidence-900", null, COMPLETED_AT, "evt-verification-1", NOW
        );

        assertThat(event.evidenceSummary()).isNull();
    }
}
