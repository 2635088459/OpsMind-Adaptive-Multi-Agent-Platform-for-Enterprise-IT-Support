package dev.opsmind.ticketworkflow.ticket.application;

import dev.opsmind.ticketworkflow.support.GetTicketFixtures;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.policy.TicketResourceAccessPolicy;
import dev.opsmind.ticketworkflow.ticket.application.policy.TicketViewPolicy;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SensitiveReadAuditPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketQueryPort;
import dev.opsmind.ticketworkflow.ticket.application.query.GetTicketQuery;
import dev.opsmind.ticketworkflow.ticket.application.service.GetTicketApplicationService;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SPEC-TW-002 §10: a missing Ticket, an out-of-ownership Ticket, and an
 * out-of-scope Ticket are all indistinguishable {@code TICKET_NOT_FOUND}
 * failures — none of them may reveal that the resource actually exists.
 */
@Tag("security")
class GetTicketResourceHidingTest {

    private TicketQueryPort ticketQueryPort;
    private GetTicketApplicationService service;

    @BeforeEach
    void setUp() {
        ticketQueryPort = mock(TicketQueryPort.class);
        service = new GetTicketApplicationService(
            ticketQueryPort,
            mock(SensitiveReadAuditPort.class),
            new TicketViewPolicy(),
            new TicketResourceAccessPolicy(),
            mock(TicketTelemetry.class),
            mock(ClockPort.class)
        );
    }

    @Test
    void shouldHideNonExistentTicketAsNotFound() {
        when(ticketQueryPort.findEmployeeView(any(), anyString())).thenReturn(Optional.empty());

        GetTicketQuery query = GetTicketFixtures.employeeQuery(
            GetTicketFixtures.DEFAULT_TICKET_ID, GetTicketFixtures.employeeActor("employee-123")
        );

        assertThatThrownBy(() -> service.get(query))
            .isInstanceOf(TicketNotFoundException.class)
            .hasMessageNotContaining(GetTicketFixtures.DEFAULT_TICKET_ID.toString());
    }

    @Test
    void shouldHideOutOfScopeSupportTicketAsNotFoundWithTheSameExceptionType() {
        when(ticketQueryPort.findSupportView(any(), any())).thenReturn(Optional.empty());

        GetTicketQuery query = GetTicketFixtures.supportQuery(
            GetTicketFixtures.DEFAULT_TICKET_ID, GetTicketFixtures.supportActor("support-100"), Set.of(ApplicationCode.VPN)
        );

        assertThatThrownBy(() -> service.get(query))
            .isInstanceOf(TicketNotFoundException.class)
            .hasMessageNotContaining(GetTicketFixtures.DEFAULT_TICKET_ID.toString());
    }
}
