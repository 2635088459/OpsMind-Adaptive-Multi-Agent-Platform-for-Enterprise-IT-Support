package dev.opsmind.ticketworkflow.ticket.domain;

import dev.opsmind.ticketworkflow.ticket.domain.event.TicketVerificationStarted;
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

/** SPEC-TW-022 domain-rules §1: {@code VERIFYING -> VERIFYING} self-transition and its invariants. */
@Tag("unit")
class TicketStartVerificationTest {

    private static final TicketId TICKET_ID = TicketId.of(UUID.randomUUID());
    private static final UUID RESOLUTION_CYCLE_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-07T18:00:00Z");
    private static final String ASSIGNEE_ID = "sam.support";

    private TicketVerificationStarted apply() {
        return Ticket.startVerification(
            TICKET_ID, TicketStatus.VERIFYING, ASSIGNEE_ID, 60L, "ver-1234", RESOLUTION_CYCLE_ID, "wf-9000",
            "tool-result-900", 1, "IDENTITY_LOGIN_CHECK", "Confirm the requester can sign in.", "SERVICE",
            "verification-orchestrator", NOW
        );
    }

    @Test
    void shouldStartVerificationOnAVerifyingTicket() {
        TicketVerificationStarted event = apply();

        assertThat(event.previousStatus()).isEqualTo(TicketStatus.VERIFYING);
        assertThat(event.newStatus()).isEqualTo(TicketStatus.VERIFYING);
        assertThat(event.assigneeId()).isEqualTo(ASSIGNEE_ID);
        assertThat(event.verificationId()).isEqualTo("ver-1234");
        assertThat(event.resolutionCycleId()).isEqualTo(RESOLUTION_CYCLE_ID);
        assertThat(event.workflowId()).isEqualTo("wf-9000");
        assertThat(event.toolResultId()).isEqualTo("tool-result-900");
        assertThat(event.attemptNumber()).isEqualTo(1);
        assertThat(event.verificationType()).isEqualTo("IDENTITY_LOGIN_CHECK");
        assertThat(event.reason()).isEqualTo("Confirm the requester can sign in.");
        assertThat(event.startedByType()).isEqualTo("SERVICE");
        assertThat(event.startedById()).isEqualTo("verification-orchestrator");
        assertThat(event.transitionId()).isEqualTo("SM-025");
        assertThat(event.reasonCode()).isEqualTo("VERIFICATION_STARTED");
        assertThat(event.aggregateVersion()).isEqualTo(61L);
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {"VERIFYING"}, mode = EnumSource.Mode.EXCLUDE)
    void shouldRejectEveryStatusOtherThanVerifying(TicketStatus currentStatus) {
        assertThatThrownBy(() -> Ticket.startVerification(
            TICKET_ID, currentStatus, ASSIGNEE_ID, 60L, "ver-1234", RESOLUTION_CYCLE_ID, "wf-9000",
            "tool-result-900", 1, "IDENTITY_LOGIN_CHECK", null, "SERVICE", "verification-orchestrator", NOW
        ))
            .isInstanceOfSatisfying(InvalidStatusTransitionException.class, ex -> {
                assertThat(ex.currentStatus()).isEqualTo(currentStatus);
                assertThat(ex.targetStatus()).isEqualTo(TicketStatus.VERIFYING);
            });
    }

    @Test
    void shouldRejectAMissingAssignee() {
        assertThatThrownBy(() -> Ticket.startVerification(
            TICKET_ID, TicketStatus.VERIFYING, null, 60L, "ver-1234", RESOLUTION_CYCLE_ID, "wf-9000",
            "tool-result-900", 1, "IDENTITY_LOGIN_CHECK", null, "SERVICE", "verification-orchestrator", NOW
        )).isInstanceOf(TicketNotAssignedException.class);
    }

    @Test
    void shouldRejectAnAttemptNumberBelowOne() {
        assertThatThrownBy(() -> Ticket.startVerification(
            TICKET_ID, TicketStatus.VERIFYING, ASSIGNEE_ID, 60L, "ver-1234", RESOLUTION_CYCLE_ID, "wf-9000",
            "tool-result-900", 0, "IDENTITY_LOGIN_CHECK", null, "SERVICE", "verification-orchestrator", NOW
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAllowANullReason() {
        TicketVerificationStarted event = Ticket.startVerification(
            TICKET_ID, TicketStatus.VERIFYING, ASSIGNEE_ID, 60L, "ver-1234", RESOLUTION_CYCLE_ID, "wf-9000",
            "tool-result-900", 2, "IDENTITY_LOGIN_CHECK", null, "SERVICE", "verification-orchestrator", NOW
        );

        assertThat(event.reason()).isNull();
        assertThat(event.attemptNumber()).isEqualTo(2);
    }
}
