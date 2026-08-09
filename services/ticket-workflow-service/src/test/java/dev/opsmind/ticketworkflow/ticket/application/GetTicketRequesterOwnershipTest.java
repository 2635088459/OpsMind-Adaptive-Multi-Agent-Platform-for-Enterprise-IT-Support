package dev.opsmind.ticketworkflow.ticket.application;

import dev.opsmind.ticketworkflow.support.GetTicketFixtures;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.policy.SensitiveReadAuditDecisionRecorder;
import dev.opsmind.ticketworkflow.ticket.application.policy.TicketResourceAccessPolicy;
import dev.opsmind.ticketworkflow.ticket.application.policy.TicketViewPolicy;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SensitiveReadAuditPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketQueryPort;
import dev.opsmind.ticketworkflow.ticket.application.query.ConditionalGetResult;
import dev.opsmind.ticketworkflow.ticket.application.query.EmployeeTicketProjection;
import dev.opsmind.ticketworkflow.ticket.application.query.GetTicketQuery;
import dev.opsmind.ticketworkflow.ticket.application.service.GetTicketApplicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SPEC-TW-002 §9: {@code ticket_id = :ticketId AND requester_id =
 * :principalSubject} — an Employee reads only Tickets they requested.
 */
@Tag("security")
class GetTicketRequesterOwnershipTest {

    private static final UUID TICKET_ID = GetTicketFixtures.DEFAULT_TICKET_ID;

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
        , mock(SensitiveReadAuditDecisionRecorder.class));
    }

    @Test
    void shouldQueryUsingAuthenticatedSubjectAsRequesterId() {
        EmployeeTicketProjection projection = GetTicketFixtures.employeeProjection(TICKET_ID);
        when(ticketQueryPort.findEmployeeView(eq(dev.opsmind.ticketworkflow.ticket.domain.value.TicketId.of(TICKET_ID)), eq("employee-123")))
            .thenReturn(Optional.of(projection));

        GetTicketQuery query = GetTicketFixtures.employeeQuery(TICKET_ID, GetTicketFixtures.employeeActor("employee-123"));

        assertThat(service.get(query)).isEqualTo(new ConditionalGetResult.Found(new dev.opsmind.ticketworkflow.ticket.application.query.GetTicketResult.Employee(projection)));
    }

    @Test
    void shouldNotAllowOneEmployeesTicketToBeReadUsingAnotherEmployeesSubject() {
        when(ticketQueryPort.findEmployeeView(eq(dev.opsmind.ticketworkflow.ticket.domain.value.TicketId.of(TICKET_ID)), eq("employee-999")))
            .thenReturn(Optional.empty());

        GetTicketQuery query = GetTicketFixtures.employeeQuery(TICKET_ID, GetTicketFixtures.employeeActor("employee-999"));

        assertThatThrownBy(() -> service.get(query)).isInstanceOf(TicketNotFoundException.class);
    }
}
