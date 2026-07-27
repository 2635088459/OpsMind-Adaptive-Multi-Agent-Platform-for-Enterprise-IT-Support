package dev.opsmind.ticketworkflow.ticket.application;

import dev.opsmind.ticketworkflow.ticket.api.exception.RequestValidationException;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketListFilters;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SPEC-TW-003 §5: filter contract — max status count, date-range order, and a stable fingerprint. */
@Tag("unit")
class ListRequesterTicketsFilterValidationTest {

    @Test
    void shouldRejectMoreThanTenStatusValues() {
        Set<TicketStatus> eleven = EnumSet.allOf(TicketStatus.class);
        assertThat(eleven.size()).isGreaterThanOrEqualTo(11);

        assertThatThrownBy(() -> new TicketListFilters(eleven, Set.of(), null, null))
            .isInstanceOf(RequestValidationException.class);
    }

    @Test
    void shouldAcceptExactlyTenStatusValues() {
        Set<TicketStatus> ten = EnumSet.copyOf(Set.of(
            TicketStatus.NEW, TicketStatus.TRIAGING, TicketStatus.INVESTIGATING, TicketStatus.WAITING_FOR_USER,
            TicketStatus.WAITING_FOR_APPROVAL, TicketStatus.EXECUTING, TicketStatus.VERIFYING, TicketStatus.RESOLVED,
            TicketStatus.CLOSED, TicketStatus.ESCALATED
        ));

        TicketListFilters filters = new TicketListFilters(ten, Set.of(), null, null);

        assertThat(filters.statuses()).hasSize(10);
    }

    @Test
    void shouldRejectCreatedFromNotBeforeCreatedTo() {
        Instant from = Instant.parse("2026-08-01T00:00:00Z");
        Instant to = Instant.parse("2026-07-01T00:00:00Z");

        assertThatThrownBy(() -> new TicketListFilters(Set.of(), Set.of(), from, to))
            .isInstanceOf(RequestValidationException.class);
    }

    @Test
    void shouldRejectEqualCreatedFromAndCreatedTo() {
        Instant same = Instant.parse("2026-07-01T00:00:00Z");

        assertThatThrownBy(() -> new TicketListFilters(Set.of(), Set.of(), same, same))
            .isInstanceOf(RequestValidationException.class);
    }

    @Test
    void fingerprintShouldBeStableRegardlessOfSetIterationOrder() {
        Set<TicketStatus> forward = new LinkedHashSet<>(Set.of(TicketStatus.NEW, TicketStatus.RESOLVED));
        Set<TicketStatus> backward = new LinkedHashSet<>(Set.of(TicketStatus.RESOLVED, TicketStatus.NEW));

        String fingerprintA = new TicketListFilters(forward, Set.of(ApplicationCode.VPN), null, null).fingerprint();
        String fingerprintB = new TicketListFilters(backward, Set.of(ApplicationCode.VPN), null, null).fingerprint();

        assertThat(fingerprintA).isEqualTo(fingerprintB);
        assertThat(fingerprintA).startsWith("sha256:");
    }

    @Test
    void fingerprintShouldDifferWhenFiltersDiffer() {
        String withStatus = new TicketListFilters(Set.of(TicketStatus.NEW), Set.of(), null, null).fingerprint();
        String withoutStatus = TicketListFilters.none().fingerprint();

        assertThat(withStatus).isNotEqualTo(withoutStatus);
    }
}
