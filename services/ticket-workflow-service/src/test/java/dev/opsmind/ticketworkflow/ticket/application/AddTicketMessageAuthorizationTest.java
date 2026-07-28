package dev.opsmind.ticketworkflow.ticket.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.support.AddTicketMessageFixtures;
import dev.opsmind.ticketworkflow.ticket.application.command.AddTicketMessageCommand;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketMessageAddedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationOutcome;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.RequestHashCalculator;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.IdempotencyRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketMessageRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketMessageWriteGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.service.AddTicketMessageApplicationService;
import dev.opsmind.ticketworkflow.ticket.domain.message.TicketMessageType;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SPEC-TW-004 §5: coarse scope per message type, plus resource-level ownership/queue scope. */
@Tag("unit")
class AddTicketMessageAuthorizationTest {

    private static final UUID TICKET_ID = AddTicketMessageFixtures.DEFAULT_TICKET_ID;

    private TicketMessageWriteGuardPort writeGuardPort;
    private TicketMessageRepository messageRepository;
    private TicketTelemetry telemetry;
    private AddTicketMessageApplicationService service;

    @BeforeEach
    void setUp() {
        messageRepository = mock(TicketMessageRepository.class);
        writeGuardPort = mock(TicketMessageWriteGuardPort.class);
        AuditRecordPort auditRecordPort = mock(AuditRecordPort.class);
        OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);
        IdempotencyRepository idempotencyRepository = mock(IdempotencyRepository.class);
        ClockPort clock = mock(ClockPort.class);
        telemetry = mock(TicketTelemetry.class);

        when(clock.now()).thenReturn(Instant.parse("2026-07-25T18:30:00Z"));
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.Reserved(UUID.randomUUID()));
        when(writeGuardPort.loadGuard(any())).thenReturn(Optional.of(
            AddTicketMessageFixtures.guard(TICKET_ID, AddTicketMessageFixtures.DEFAULT_REQUESTER, ApplicationCode.HOUSING_PORTAL, TicketStatus.INVESTIGATING)
        ));

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new AddTicketMessageApplicationService(
            messageRepository, writeGuardPort, auditRecordPort, outboxEventRepository, idempotencyRepository,
            clock, new RequestHashCalculator(objectMapper), new TicketMessageAddedEventMapper(), telemetry, objectMapper
        );
    }

    @Test
    void shouldRejectEmployeeMissingMessageSelfScope() {
        AddTicketMessageCommand command = AddTicketMessageFixtures.employeeCommand(
            TICKET_ID, AddTicketMessageFixtures.employeeActorWithoutScope(AddTicketMessageFixtures.DEFAULT_REQUESTER), "key-1"
        );

        assertThatThrownBy(() -> service.addMessage(command)).isInstanceOf(TicketAuthorizationException.class);
        verify(writeGuardPort, never()).loadGuard(any());
        verify(telemetry).recordMessageAuthorizationDenied();
    }

    @Test
    void shouldRejectSupportMissingPublicScopeForPublicSupportMessage() {
        AddTicketMessageCommand command = AddTicketMessageFixtures.supportCommand(
            TICKET_ID, AddTicketMessageFixtures.supportActor("support-100", AddTicketMessageFixtures.SUPPORT_INTERNAL_SCOPE),
            TicketMessageType.PUBLIC_SUPPORT_MESSAGE, Set.of(ApplicationCode.HOUSING_PORTAL), "key-2"
        );

        assertThatThrownBy(() -> service.addMessage(command)).isInstanceOf(TicketAuthorizationException.class);
    }

    @Test
    void shouldRejectSupportMissingInternalScopeForInternalNote() {
        AddTicketMessageCommand command = AddTicketMessageFixtures.supportCommand(
            TICKET_ID, AddTicketMessageFixtures.supportActor("support-100", AddTicketMessageFixtures.SUPPORT_PUBLIC_SCOPE),
            TicketMessageType.INTERNAL_SUPPORT_NOTE, Set.of(ApplicationCode.HOUSING_PORTAL), "key-3"
        );

        assertThatThrownBy(() -> service.addMessage(command)).isInstanceOf(TicketAuthorizationException.class);
    }

    @Test
    void shouldHideTicketFromEmployeeWhoDoesNotOwnIt() {
        AddTicketMessageCommand command = AddTicketMessageFixtures.employeeCommand(
            TICKET_ID, AddTicketMessageFixtures.employeeActor("employee-999"), "key-4"
        );

        assertThatThrownBy(() -> service.addMessage(command)).isInstanceOf(TicketNotFoundException.class);
        verify(messageRepository, never()).save(any());
    }

    @Test
    void shouldHideTicketFromSupportOutsideAuthorizedApplicationScope() {
        AddTicketMessageCommand command = AddTicketMessageFixtures.supportCommand(
            TICKET_ID, AddTicketMessageFixtures.supportActor("support-100", AddTicketMessageFixtures.SUPPORT_PUBLIC_SCOPE),
            TicketMessageType.PUBLIC_SUPPORT_MESSAGE, Set.of(ApplicationCode.VPN), "key-5"
        );

        assertThatThrownBy(() -> service.addMessage(command)).isInstanceOf(TicketNotFoundException.class);
    }

    @Test
    void shouldAllowSupportWithinAuthorizedApplicationScope() {
        AddTicketMessageCommand command = AddTicketMessageFixtures.supportCommand(
            TICKET_ID, AddTicketMessageFixtures.supportActor("support-100", AddTicketMessageFixtures.SUPPORT_PUBLIC_SCOPE),
            TicketMessageType.PUBLIC_SUPPORT_MESSAGE, Set.of(ApplicationCode.HOUSING_PORTAL), "key-6"
        );

        assertThatCode(() -> service.addMessage(command)).doesNotThrowAnyException();
    }
}
