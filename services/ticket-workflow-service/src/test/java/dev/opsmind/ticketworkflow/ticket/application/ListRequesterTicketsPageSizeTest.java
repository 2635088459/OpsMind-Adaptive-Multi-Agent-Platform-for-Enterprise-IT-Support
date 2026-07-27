package dev.opsmind.ticketworkflow.ticket.application;

import dev.opsmind.ticketworkflow.support.ListRequesterTicketsFixtures;
import dev.opsmind.ticketworkflow.ticket.api.exception.RequestValidationException;
import dev.opsmind.ticketworkflow.ticket.application.query.ListRequesterTicketsQuery;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketListFilters;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SPEC-TW-003 §6: page size is 1-50 (default 20), out-of-range is 400 VALIDATION_ERROR. */
@Tag("unit")
class ListRequesterTicketsPageSizeTest {

    @ParameterizedTest
    @ValueSource(ints = {1, 20, 50})
    void shouldAcceptLimitsWithinRange(int limit) {
        ListRequesterTicketsQuery query = new ListRequesterTicketsQuery(
            ListRequesterTicketsFixtures.employeeActor("employee-123"), TicketListFilters.none(), limit, null
        );

        assertThat(query.limit()).isEqualTo(limit);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 51, 100})
    void shouldRejectLimitsOutsideRange(int limit) {
        assertThatThrownBy(() -> new ListRequesterTicketsQuery(
            ListRequesterTicketsFixtures.employeeActor("employee-123"), TicketListFilters.none(), limit, null
        )).isInstanceOf(RequestValidationException.class);
    }

    @Test
    void shouldRejectNullActorAndFilters() {
        assertThatThrownBy(() -> new ListRequesterTicketsQuery(null, TicketListFilters.none(), 20, null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ListRequesterTicketsQuery(
            ListRequesterTicketsFixtures.employeeActor("employee-123"), null, 20, null
        )).isInstanceOf(NullPointerException.class);
    }
}
