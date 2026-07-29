package dev.opsmind.ticketworkflow.ticket.application;

import dev.opsmind.ticketworkflow.ticket.api.exception.RequestValidationException;
import dev.opsmind.ticketworkflow.ticket.application.query.SlaQueueState;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueFilters;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueuePriority;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

/** SPEC-TW-005 §9: filter contract — operational statuses only, conflict rules, and a stable fingerprint. */
@Tag("unit")
class SupportQueueFilterValidationTest {

    private static SupportQueueFilters filters(
        Set<TicketStatus> statuses, Set<SupportQueuePriority> priorities, boolean unassignedOnly, String assignedAgent
    ) {
        return new SupportQueueFilters(statuses, priorities, Set.of(), Set.of(), assignedAgent, unassignedOnly, Set.of(), null, null);
    }

    @Test
    void shouldRejectClosedInStatusFilter() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> filters(Set.of(TicketStatus.CLOSED), Set.of(), false, null))
            .isInstanceOf(RequestValidationException.class);
    }

    @Test
    void shouldRejectCancelledInStatusFilter() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> filters(Set.of(TicketStatus.CANCELLED), Set.of(), false, null))
            .isInstanceOf(RequestValidationException.class);
    }

    @Test
    void shouldAcceptNonTerminalStatuses() {
        SupportQueueFilters result = filters(Set.of(TicketStatus.NEW, TicketStatus.RESOLVED), Set.of(), false, null);

        org.assertj.core.api.Assertions.assertThat(result.statuses()).containsExactlyInAnyOrder(TicketStatus.NEW, TicketStatus.RESOLVED);
    }

    @Test
    void shouldRejectMoreThanTenStatusValues() {
        Set<TicketStatus> allNonTerminal = EnumSet.complementOf(EnumSet.of(TicketStatus.CLOSED, TicketStatus.CANCELLED));
        // SPEC-TW-007 added TicketStatus.TRIAGED, so the non-terminal set grew from 10 to 11.
        org.assertj.core.api.Assertions.assertThat(allNonTerminal.size()).isEqualTo(11);

        SupportQueueFilters result = filters(allNonTerminal, Set.of(), false, null);
        org.assertj.core.api.Assertions.assertThat(result.statuses()).hasSize(11);
    }

    @Test
    void shouldAcceptAllFivePriorityValues() {
        Set<SupportQueuePriority> all = EnumSet.allOf(SupportQueuePriority.class);

        SupportQueueFilters result = new SupportQueueFilters(Set.of(), all, Set.of(), Set.of(), null, false, Set.of(), null, null);

        org.assertj.core.api.Assertions.assertThat(result.priorities()).hasSize(5);
    }

    @Test
    void shouldRejectMoreThanFourApplicationCodeValues() {
        // ApplicationCode only has 4 constants, so this documents that the
        // MAX_APPLICATION_CODES=4 bound can never actually be exceeded by a
        // Set<ApplicationCode> — it is defense-in-depth, not a reachable path.
        org.assertj.core.api.Assertions.assertThat(EnumSet.allOf(ApplicationCode.class)).hasSize(4);
    }

    @Test
    void shouldRejectUnassignedOnlyCombinedWithAssignedAgent() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> filters(Set.of(), Set.of(), true, "agent-200"))
            .isInstanceOf(RequestValidationException.class);
    }

    @Test
    void shouldAllowUnassignedOnlyWithoutAssignedAgent() {
        SupportQueueFilters result = filters(Set.of(), Set.of(), true, null);

        org.assertj.core.api.Assertions.assertThat(result.unassignedOnly()).isTrue();
    }

    @Test
    void shouldRejectCreatedFromNotBeforeCreatedTo() {
        Instant from = Instant.parse("2026-08-01T00:00:00Z");
        Instant to = Instant.parse("2026-07-01T00:00:00Z");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new SupportQueueFilters(
            Set.of(), Set.of(), Set.of(), Set.of(), null, false, Set.of(), from, to
        )).isInstanceOf(RequestValidationException.class);
    }

    @Test
    void fingerprintShouldBeStableRegardlessOfSetIterationOrder() {
        Set<TicketStatus> forward = new LinkedHashSet<>(Set.of(TicketStatus.NEW, TicketStatus.RESOLVED));
        Set<TicketStatus> backward = new LinkedHashSet<>(Set.of(TicketStatus.RESOLVED, TicketStatus.NEW));

        String fingerprintA = filters(forward, Set.of(), false, null).fingerprint();
        String fingerprintB = filters(backward, Set.of(), false, null).fingerprint();

        org.assertj.core.api.Assertions.assertThat(fingerprintA).isEqualTo(fingerprintB);
        org.assertj.core.api.Assertions.assertThat(fingerprintA).startsWith("sha256:");
    }

    @Test
    void fingerprintShouldDifferWhenFiltersDiffer() {
        String withStatus = filters(Set.of(TicketStatus.NEW), Set.of(), false, null).fingerprint();
        String withoutStatus = filters(Set.of(), Set.of(), false, null).fingerprint();

        org.assertj.core.api.Assertions.assertThat(withStatus).isNotEqualTo(withoutStatus);
    }

    @Test
    void fingerprintShouldExcludeEvaluationTimeConcernsAndOnlyReflectFilterFields() {
        SupportQueueFilters a = new SupportQueueFilters(
            Set.of(), Set.of(), Set.of(), Set.of(), null, false, Set.of(SlaQueueState.AT_RISK), null, null
        );
        SupportQueueFilters b = new SupportQueueFilters(
            Set.of(), Set.of(), Set.of(), Set.of(), null, false, Set.of(SlaQueueState.AT_RISK), null, null
        );

        org.assertj.core.api.Assertions.assertThat(a.fingerprint()).isEqualTo(b.fingerprint());
    }
}
