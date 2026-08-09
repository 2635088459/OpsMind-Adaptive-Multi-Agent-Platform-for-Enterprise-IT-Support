package dev.opsmind.ticketworkflow.ticket.application;

import dev.opsmind.ticketworkflow.support.GetTicketFixtures;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.policy.SensitiveReadAuditDecisionRecorder;
import dev.opsmind.ticketworkflow.ticket.application.policy.TicketResourceAccessPolicy;
import dev.opsmind.ticketworkflow.ticket.application.policy.TicketViewPolicy;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SensitiveReadAuditPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketQueryPort;
import dev.opsmind.ticketworkflow.ticket.application.query.GetTicketQuery;
import dev.opsmind.ticketworkflow.ticket.application.service.GetTicketApplicationService;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** SPEC-TW-002 §8: missing coarse-grained read scope is a hard 403. */
@Tag("security")
class GetTicketMissingScopeTest {

    @Test
    void shouldRejectEmployeeWithoutTicketsReadSelfScope() {
        TicketQueryPort ticketQueryPort = mock(TicketQueryPort.class);
        GetTicketApplicationService service = new GetTicketApplicationService(
            ticketQueryPort,
            mock(SensitiveReadAuditPort.class),
            new TicketViewPolicy(),
            new TicketResourceAccessPolicy(),
            mock(TicketTelemetry.class),
            mock(ClockPort.class)
        , mock(SensitiveReadAuditDecisionRecorder.class));

        GetTicketQuery query = GetTicketFixtures.employeeQuery(
            GetTicketFixtures.DEFAULT_TICKET_ID, GetTicketFixtures.employeeActorWithoutReadScope("employee-123")
        );

        assertThatThrownBy(() -> service.get(query)).isInstanceOf(TicketAuthorizationException.class);
        verify(ticketQueryPort, never()).findEmployeeView(any(), anyString());
    }

    @Test
    void shouldRejectSupportWithoutTicketsReadQueueScope() {
        TicketQueryPort ticketQueryPort = mock(TicketQueryPort.class);
        GetTicketApplicationService service = new GetTicketApplicationService(
            ticketQueryPort,
            mock(SensitiveReadAuditPort.class),
            new TicketViewPolicy(),
            new TicketResourceAccessPolicy(),
            mock(TicketTelemetry.class),
            mock(ClockPort.class)
        , mock(SensitiveReadAuditDecisionRecorder.class));

        ActorContext supportWithoutScope = new ActorContext("IT_SUPPORT", "support-100", "support-console", Set.of());
        GetTicketQuery query = GetTicketFixtures.supportQuery(
            GetTicketFixtures.DEFAULT_TICKET_ID, supportWithoutScope, Set.of()
        );

        assertThatThrownBy(() -> service.get(query)).isInstanceOf(TicketAuthorizationException.class);
        verify(ticketQueryPort, never()).findSupportView(any(), any());
    }
}
