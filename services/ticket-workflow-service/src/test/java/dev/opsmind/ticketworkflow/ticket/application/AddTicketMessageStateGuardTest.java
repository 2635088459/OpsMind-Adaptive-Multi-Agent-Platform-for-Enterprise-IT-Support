package dev.opsmind.ticketworkflow.ticket.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.support.AddTicketMessageFixtures;
import dev.opsmind.ticketworkflow.ticket.application.command.AddTicketMessageCommand;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketMessageAddedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketMessageNotAllowedInStateException;
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
import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SPEC-TW-004 §8: CLOSED and CANCELLED reject messages; every other status allows them. */
@Tag("unit")
class AddTicketMessageStateGuardTest {

    private static final UUID TICKET_ID = AddTicketMessageFixtures.DEFAULT_TICKET_ID;

    private TicketMessageWriteGuardPort writeGuardPort;
    private TicketMessageRepository messageRepository;
    private AddTicketMessageApplicationService service;

    @BeforeEach
    void setUp() {
        messageRepository = mock(TicketMessageRepository.class);
        writeGuardPort = mock(TicketMessageWriteGuardPort.class);
        AuditRecordPort auditRecordPort = mock(AuditRecordPort.class);
        OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);
        IdempotencyRepository idempotencyRepository = mock(IdempotencyRepository.class);
        ClockPort clock = mock(ClockPort.class);
        TicketTelemetry telemetry = mock(TicketTelemetry.class);

        when(clock.now()).thenReturn(Instant.parse("2026-07-25T18:30:00Z"));
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.Reserved(UUID.randomUUID()));

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new AddTicketMessageApplicationService(
            messageRepository, writeGuardPort, auditRecordPort, outboxEventRepository, idempotencyRepository,
            clock, new RequestHashCalculator(objectMapper), new TicketMessageAddedEventMapper(), telemetry, objectMapper
        );
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {"CLOSED", "CANCELLED"})
    void shouldRejectMessagesOnTerminalStatuses(TicketStatus status) {
        when(writeGuardPort.loadGuard(any())).thenReturn(Optional.of(
            AddTicketMessageFixtures.guard(TICKET_ID, AddTicketMessageFixtures.DEFAULT_REQUESTER, ApplicationCode.HOUSING_PORTAL, status)
        ));
        AddTicketMessageCommand command = AddTicketMessageFixtures.employeeCommand(
            TICKET_ID, AddTicketMessageFixtures.employeeActor(AddTicketMessageFixtures.DEFAULT_REQUESTER), "key-" + status
        );

        assertThatThrownBy(() -> service.addMessage(command)).isInstanceOf(TicketMessageNotAllowedInStateException.class);
        verify(messageRepository, never()).save(any());
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {"CLOSED", "CANCELLED"}, mode = EnumSource.Mode.EXCLUDE)
    void shouldAllowMessagesOnEveryNonTerminalStatus(TicketStatus status) {
        when(writeGuardPort.loadGuard(any())).thenReturn(Optional.of(
            AddTicketMessageFixtures.guard(TICKET_ID, AddTicketMessageFixtures.DEFAULT_REQUESTER, ApplicationCode.HOUSING_PORTAL, status)
        ));
        AddTicketMessageCommand command = AddTicketMessageFixtures.employeeCommand(
            TICKET_ID, AddTicketMessageFixtures.employeeActor(AddTicketMessageFixtures.DEFAULT_REQUESTER), "key-" + status
        );

        assertThatCode(() -> service.addMessage(command)).doesNotThrowAnyException();
    }
}
