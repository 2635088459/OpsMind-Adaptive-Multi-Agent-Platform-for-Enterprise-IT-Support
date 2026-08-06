package dev.opsmind.ticketworkflow.ticket.domain;

import dev.opsmind.ticketworkflow.ticket.domain.event.TicketResolvedWithVerification;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidStatusTransitionException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.TicketNotAssignedException;
import dev.opsmind.ticketworkflow.ticket.domain.model.Ticket;
import dev.opsmind.ticketworkflow.ticket.domain.value.ResolutionCode;
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

/** SPEC-TW-025 domain-rules §1: {@code VERIFYING -> RESOLVED} and its invariants. Mirrors {@code TicketResolutionTest}'s (SPEC-TW-010) shape. */
@Tag("unit")
class TicketResolveWithVerificationTest {

    private static final TicketId TICKET_ID = TicketId.of(UUID.randomUUID());
    private static final UUID RESOLUTION_CYCLE_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-06T19:05:00Z");
    private static final Instant AUTO_CLOSE_DUE_AT = NOW.plusSeconds(604_800);
    private static final String ACTOR_TYPE = "SERVICE";
    private static final String ACTOR_ID = "verification-orchestrator";
    private static final String ASSIGNEE_ID = "sam.support";
    private static final String VERIFICATION_ID = "ver-1234";
    private static final String VERIFICATION_EVIDENCE_ID = "ve-300";
    private static final String SUMMARY = "Verification confirmed the requester can sign in after MFA reset.";

    private TicketResolvedWithVerification resolveWithVerification() {
        return Ticket.resolveWithVerification(
            TICKET_ID, TicketStatus.VERIFYING, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 17L,
            VERIFICATION_ID, VERIFICATION_EVIDENCE_ID, ResolutionCode.FIXED, SUMMARY, AUTO_CLOSE_DUE_AT, ACTOR_TYPE, ACTOR_ID, NOW
        );
    }

    @Test
    void shouldResolveAVerifyingTicket() {
        TicketResolvedWithVerification event = resolveWithVerification();

        assertThat(event.previousStatus()).isEqualTo(TicketStatus.VERIFYING);
        assertThat(event.newStatus()).isEqualTo(TicketStatus.RESOLVED);
        assertThat(event.assigneeId()).isEqualTo(ASSIGNEE_ID);
        assertThat(event.resolutionCycleId()).isEqualTo(RESOLUTION_CYCLE_ID);
        assertThat(event.verificationId()).isEqualTo(VERIFICATION_ID);
        assertThat(event.verificationEvidenceId()).isEqualTo(VERIFICATION_EVIDENCE_ID);
        assertThat(event.resolutionCode()).isEqualTo(ResolutionCode.FIXED);
        assertThat(event.resolutionSummary()).isEqualTo(SUMMARY);
        assertThat(event.resolvedByType()).isEqualTo(ACTOR_TYPE);
        assertThat(event.resolvedById()).isEqualTo(ACTOR_ID);
        assertThat(event.resolvedAt()).isEqualTo(NOW);
        assertThat(event.autoCloseDueAt()).isEqualTo(AUTO_CLOSE_DUE_AT);
        assertThat(event.transitionId()).isEqualTo("SM-030");
        assertThat(event.reasonCode()).isEqualTo("VERIFIED_RESOLUTION");
        assertThat(event.aggregateVersion()).isEqualTo(18L);
    }

    @Test
    void shouldTrimTheResolutionSummary() {
        TicketResolvedWithVerification event = Ticket.resolveWithVerification(
            TICKET_ID, TicketStatus.VERIFYING, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 17L,
            VERIFICATION_ID, VERIFICATION_EVIDENCE_ID, ResolutionCode.FIXED, "   " + SUMMARY + "   ",
            AUTO_CLOSE_DUE_AT, ACTOR_TYPE, ACTOR_ID, NOW
        );

        assertThat(event.resolutionSummary()).isEqualTo(SUMMARY);
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {"VERIFYING"}, mode = EnumSource.Mode.EXCLUDE)
    void shouldRejectEveryStatusOtherThanVerifying(TicketStatus currentStatus) {
        assertThatThrownBy(() -> Ticket.resolveWithVerification(
            TICKET_ID, currentStatus, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 17L,
            VERIFICATION_ID, VERIFICATION_EVIDENCE_ID, ResolutionCode.FIXED, SUMMARY, AUTO_CLOSE_DUE_AT, ACTOR_TYPE, ACTOR_ID, NOW
        ))
            .isInstanceOfSatisfying(InvalidStatusTransitionException.class, ex -> {
                assertThat(ex.currentStatus()).isEqualTo(currentStatus);
                assertThat(ex.targetStatus()).isEqualTo(TicketStatus.RESOLVED);
            });
    }

    @Test
    void shouldRejectAMissingAssignee() {
        assertThatThrownBy(() -> Ticket.resolveWithVerification(
            TICKET_ID, TicketStatus.VERIFYING, null, RESOLUTION_CYCLE_ID, 17L,
            VERIFICATION_ID, VERIFICATION_EVIDENCE_ID, ResolutionCode.FIXED, SUMMARY, AUTO_CLOSE_DUE_AT, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(TicketNotAssignedException.class);
    }

    @Test
    void shouldRejectAMissingResolutionCycle() {
        assertThatThrownBy(() -> Ticket.resolveWithVerification(
            TICKET_ID, TicketStatus.VERIFYING, ASSIGNEE_ID, null, 17L,
            VERIFICATION_ID, VERIFICATION_EVIDENCE_ID, ResolutionCode.FIXED, SUMMARY, AUTO_CLOSE_DUE_AT, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectAMissingVerificationId() {
        assertThatThrownBy(() -> Ticket.resolveWithVerification(
            TICKET_ID, TicketStatus.VERIFYING, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 17L,
            null, VERIFICATION_EVIDENCE_ID, ResolutionCode.FIXED, SUMMARY, AUTO_CLOSE_DUE_AT, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectAMissingVerificationEvidenceId() {
        assertThatThrownBy(() -> Ticket.resolveWithVerification(
            TICKET_ID, TicketStatus.VERIFYING, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 17L,
            VERIFICATION_ID, null, ResolutionCode.FIXED, SUMMARY, AUTO_CLOSE_DUE_AT, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectANullResolutionCode() {
        assertThatThrownBy(() -> Ticket.resolveWithVerification(
            TICKET_ID, TicketStatus.VERIFYING, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 17L,
            VERIFICATION_ID, VERIFICATION_EVIDENCE_ID, null, SUMMARY, AUTO_CLOSE_DUE_AT, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "too short", "123456789"})
    void shouldRejectABlankOrTooShortSummary(String summary) {
        assertThatThrownBy(() -> Ticket.resolveWithVerification(
            TICKET_ID, TicketStatus.VERIFYING, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 17L,
            VERIFICATION_ID, VERIFICATION_EVIDENCE_ID, ResolutionCode.FIXED, summary, AUTO_CLOSE_DUE_AT, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectATooLongSummary() {
        String tooLong = "a".repeat(5001);
        assertThatThrownBy(() -> Ticket.resolveWithVerification(
            TICKET_ID, TicketStatus.VERIFYING, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 17L,
            VERIFICATION_ID, VERIFICATION_EVIDENCE_ID, ResolutionCode.FIXED, tooLong, AUTO_CLOSE_DUE_AT, ACTOR_TYPE, ACTOR_ID, NOW
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAcceptTheBoundaryLengthsOfTenAndFiveThousandCharacters() {
        String min = "a".repeat(10);
        String max = "a".repeat(5000);

        assertThat(Ticket.resolveWithVerification(
            TICKET_ID, TicketStatus.VERIFYING, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 17L,
            VERIFICATION_ID, VERIFICATION_EVIDENCE_ID, ResolutionCode.FIXED, min, AUTO_CLOSE_DUE_AT, ACTOR_TYPE, ACTOR_ID, NOW
        ).resolutionSummary()).hasSize(10);

        assertThat(Ticket.resolveWithVerification(
            TICKET_ID, TicketStatus.VERIFYING, ASSIGNEE_ID, RESOLUTION_CYCLE_ID, 17L,
            VERIFICATION_ID, VERIFICATION_EVIDENCE_ID, ResolutionCode.FIXED, max, AUTO_CLOSE_DUE_AT, ACTOR_TYPE, ACTOR_ID, NOW
        ).resolutionSummary()).hasSize(5000);
    }
}
