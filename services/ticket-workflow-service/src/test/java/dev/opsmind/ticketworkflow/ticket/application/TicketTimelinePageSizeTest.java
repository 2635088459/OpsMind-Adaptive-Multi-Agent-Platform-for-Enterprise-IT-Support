package dev.opsmind.ticketworkflow.ticket.application;

import dev.opsmind.ticketworkflow.support.TicketTimelineFixtures;
import dev.opsmind.ticketworkflow.ticket.api.exception.RequestValidationException;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineQuery;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SPEC-TW-006 §14: page size is 1-100 (default 50), out-of-range is 400 VALIDATION_ERROR. */
@Tag("unit")
class TicketTimelinePageSizeTest {

    private static final UUID TICKET_ID = UUID.randomUUID();

    @ParameterizedTest
    @ValueSource(ints = {1, 50, 100})
    void shouldAcceptLimitsWithinRange(int limit) {
        TicketTimelineQuery query = new TicketTimelineQuery(
            TicketId.of(TICKET_ID), TicketTimelineFixtures.employeeActor("employee-123"), Set.of(), limit, null
        );

        assertThat(query.limit()).isEqualTo(limit);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 101, 200})
    void shouldRejectLimitsOutsideRange(int limit) {
        assertThatThrownBy(() -> new TicketTimelineQuery(
            TicketId.of(TICKET_ID), TicketTimelineFixtures.employeeActor("employee-123"), Set.of(), limit, null
        )).isInstanceOf(RequestValidationException.class);
    }

    @Test
    void shouldRejectNullTicketIdAndActor() {
        assertThatThrownBy(() -> new TicketTimelineQuery(null, TicketTimelineFixtures.employeeActor("employee-123"), Set.of(), 50, null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TicketTimelineQuery(TicketId.of(TICKET_ID), null, Set.of(), 50, null))
            .isInstanceOf(NullPointerException.class);
    }
}
