package dev.opsmind.ticketworkflow.ticket.application;

import dev.opsmind.ticketworkflow.support.GetTicketFixtures;
import dev.opsmind.ticketworkflow.ticket.application.exception.SensitiveReadAuditFailureException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.model.SensitiveReadAuditEntry;
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
import dev.opsmind.ticketworkflow.ticket.application.query.GetTicketResult;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportTicketProjection;
import dev.opsmind.ticketworkflow.ticket.application.service.GetTicketApplicationService;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
class GetTicketApplicationServiceTest {

    private static final UUID TICKET_ID = GetTicketFixtures.DEFAULT_TICKET_ID;

    private TicketQueryPort ticketQueryPort;
    private SensitiveReadAuditPort sensitiveReadAuditPort;
    private TicketTelemetry telemetry;
    private ClockPort clock;
    private GetTicketApplicationService service;

    @BeforeEach
    void setUp() {
        ticketQueryPort = mock(TicketQueryPort.class);
        sensitiveReadAuditPort = mock(SensitiveReadAuditPort.class);
        telemetry = mock(TicketTelemetry.class);
        clock = mock(ClockPort.class);
        when(clock.now()).thenReturn(Instant.parse("2026-07-23T16:30:00Z"));

        service = new GetTicketApplicationService(
            ticketQueryPort,
            sensitiveReadAuditPort,
            new TicketViewPolicy(),
            new TicketResourceAccessPolicy(),
            telemetry,
            clock
        , mock(SensitiveReadAuditDecisionRecorder.class));
    }

    @Test
    void shouldReturnEmployeeViewForOwnedTicket() {
        EmployeeTicketProjection projection = GetTicketFixtures.employeeProjection(TICKET_ID);
        when(ticketQueryPort.findEmployeeView(any(), eq("employee-123"))).thenReturn(Optional.of(projection));

        GetTicketQuery query = GetTicketFixtures.employeeQuery(TICKET_ID, GetTicketFixtures.employeeActor("employee-123"));
        ConditionalGetResult result = service.get(query);

        assertThat(result).isInstanceOf(ConditionalGetResult.Found.class);
        GetTicketResult found = ((ConditionalGetResult.Found) result).result();
        assertThat(found).isInstanceOf(GetTicketResult.Employee.class);
        assertThat(((GetTicketResult.Employee) found).projection()).isEqualTo(projection);
        verify(sensitiveReadAuditPort, never()).recordSensitiveRead(any());
        verify(telemetry).recordGet(dev.opsmind.ticketworkflow.ticket.application.query.TicketViewType.EMPLOYEE_VIEW);
    }

    @Test
    void shouldRejectEmployeeReadingAnotherEmployeesTicketAsNotFound() {
        when(ticketQueryPort.findEmployeeView(any(), eq("employee-999"))).thenReturn(Optional.empty());

        GetTicketQuery query = GetTicketFixtures.employeeQuery(TICKET_ID, GetTicketFixtures.employeeActor("employee-999"));

        assertThatThrownBy(() -> service.get(query)).isInstanceOf(TicketNotFoundException.class);
        verify(telemetry).recordGetNotFound();
    }

    @Test
    void shouldReturnSupportViewAndCreateSensitiveReadAuditForAuthorizedQueue() {
        SupportTicketProjection projection = GetTicketFixtures.supportProjection(TICKET_ID, "employee-123");
        when(ticketQueryPort.findSupportView(any(), eq(Set.of(ApplicationCode.HOUSING_PORTAL))))
            .thenReturn(Optional.of(projection));

        GetTicketQuery query = GetTicketFixtures.supportQuery(
            TICKET_ID, GetTicketFixtures.supportActor("support-100"), Set.of(ApplicationCode.HOUSING_PORTAL)
        );

        ConditionalGetResult result = service.get(query);

        assertThat(result).isInstanceOf(ConditionalGetResult.Found.class);
        GetTicketResult found = ((ConditionalGetResult.Found) result).result();
        assertThat(found).isInstanceOf(GetTicketResult.Support.class);

        ArgumentCaptor<SensitiveReadAuditEntry> captor = ArgumentCaptor.forClass(SensitiveReadAuditEntry.class);
        verify(sensitiveReadAuditPort).recordSensitiveRead(captor.capture());
        assertThat(captor.getValue().actorId()).isEqualTo("support-100");
        assertThat(captor.getValue().resourceId()).isEqualTo(TICKET_ID.toString());
        assertThat(captor.getValue().viewType()).isEqualTo("SUPPORT_VIEW");
        assertThat(captor.getValue().outcome()).isEqualTo("SUCCESS");
    }

    @Test
    void shouldReturnNotFoundWhenSupportHasNoAllowedApplicationCodes() {
        GetTicketQuery query = GetTicketFixtures.supportQuery(
            TICKET_ID, GetTicketFixtures.supportActor("support-100"), Set.of()
        );

        assertThatThrownBy(() -> service.get(query)).isInstanceOf(TicketNotFoundException.class);
        verify(ticketQueryPort, never()).findSupportView(any(), any());
    }

    @Test
    void shouldRejectSupportOutsideAuthorizedApplicationScopeAsNotFound() {
        when(ticketQueryPort.findSupportView(any(), eq(Set.of(ApplicationCode.VPN)))).thenReturn(Optional.empty());

        GetTicketQuery query = GetTicketFixtures.supportQuery(
            TICKET_ID, GetTicketFixtures.supportActor("support-100"), Set.of(ApplicationCode.VPN)
        );

        assertThatThrownBy(() -> service.get(query)).isInstanceOf(TicketNotFoundException.class);
        verify(sensitiveReadAuditPort, never()).recordSensitiveRead(any());
    }

    @Test
    void shouldRejectEmployeeMissingReadScope() {
        GetTicketQuery query = GetTicketFixtures.employeeQuery(
            TICKET_ID, GetTicketFixtures.employeeActorWithoutReadScope("employee-123")
        );

        assertThatThrownBy(() -> service.get(query)).isInstanceOf(TicketAuthorizationException.class);
        verify(ticketQueryPort, never()).findEmployeeView(any(), anyString());
        verify(telemetry).recordGetAuthorizationDenied();
    }

    @Test
    void shouldRejectAuditorAsUnimplementedViewWithoutLeakingFields() {
        GetTicketQuery query = GetTicketFixtures.employeeQuery(TICKET_ID, GetTicketFixtures.auditorActor("auditor-1"));

        assertThatThrownBy(() -> service.get(query)).isInstanceOf(TicketAuthorizationException.class);
        verify(ticketQueryPort, never()).findEmployeeView(any(), anyString());
        verify(ticketQueryPort, never()).findSupportView(any(), any());
        verify(sensitiveReadAuditPort, never()).recordSensitiveRead(any());
    }

    @Test
    void shouldFailClosedWhenRequiredSensitiveReadAuditFails() {
        SupportTicketProjection projection = GetTicketFixtures.supportProjection(TICKET_ID, "employee-123");
        when(ticketQueryPort.findSupportView(any(), any())).thenReturn(Optional.of(projection));
        doThrow(new RuntimeException("db down")).when(sensitiveReadAuditPort).recordSensitiveRead(any());

        GetTicketQuery query = GetTicketFixtures.supportQuery(
            TICKET_ID, GetTicketFixtures.supportActor("support-100"), Set.of(ApplicationCode.HOUSING_PORTAL)
        );

        assertThatThrownBy(() -> service.get(query)).isInstanceOf(SensitiveReadAuditFailureException.class);
        verify(telemetry).recordSensitiveReadAuditFailure();
    }
}
