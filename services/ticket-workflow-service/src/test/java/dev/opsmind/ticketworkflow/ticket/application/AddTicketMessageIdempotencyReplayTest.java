package dev.opsmind.ticketworkflow.ticket.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.support.AddTicketMessageFixtures;
import dev.opsmind.ticketworkflow.ticket.application.command.AddTicketMessageCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.AddTicketMessageResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketMessageAddedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.RequestInProgressException;
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
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SPEC-TW-004 §11: replay returns the original response; a reused key with a different payload is rejected. */
@Tag("unit")
class AddTicketMessageIdempotencyReplayTest {

    private static final UUID TICKET_ID = AddTicketMessageFixtures.DEFAULT_TICKET_ID;

    private TicketMessageRepository messageRepository;
    private IdempotencyRepository idempotencyRepository;
    private AddTicketMessageApplicationService service;

    @BeforeEach
    void setUp() {
        messageRepository = mock(TicketMessageRepository.class);
        TicketMessageWriteGuardPort writeGuardPort = mock(TicketMessageWriteGuardPort.class);
        AuditRecordPort auditRecordPort = mock(AuditRecordPort.class);
        OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);
        idempotencyRepository = mock(IdempotencyRepository.class);
        ClockPort clock = mock(ClockPort.class);
        TicketTelemetry telemetry = mock(TicketTelemetry.class);

        when(clock.now()).thenReturn(Instant.parse("2026-07-25T18:30:00Z"));
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
    void shouldReturnOriginalResponseOnReplayWithoutCreatingANewMessage() {
        UUID originalMessageId = UUID.randomUUID();
        String storedJson = """
            {"messageId":"%s","ticketId":"%s","messageType":"PUBLIC_REQUESTER_MESSAGE","visibility":"PUBLIC",\
            "authorType":"EMPLOYEE","content":"%s","createdAt":"2026-07-25T18:00:00Z","version":0}
            """.formatted(originalMessageId, TICKET_ID, AddTicketMessageFixtures.DEFAULT_CONTENT);
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.Replayed(201, storedJson));

        AddTicketMessageCommand command = AddTicketMessageFixtures.employeeCommand(
            TICKET_ID, AddTicketMessageFixtures.employeeActor(AddTicketMessageFixtures.DEFAULT_REQUESTER), "same-key"
        );

        AddTicketMessageResult result = service.addMessage(command);

        assertThat(result.messageId().value()).isEqualTo(originalMessageId);
        assertThat(result.idempotencyReplayed()).isTrue();
        verify(messageRepository, never()).save(any());
    }

    @Test
    void shouldRejectSameKeyWithDifferentPayload() {
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.KeyReused());

        AddTicketMessageCommand command = AddTicketMessageFixtures.employeeCommand(
            TICKET_ID, AddTicketMessageFixtures.employeeActor(AddTicketMessageFixtures.DEFAULT_REQUESTER), "same-key"
        );

        assertThatThrownBy(() -> service.addMessage(command)).isInstanceOf(IdempotencyKeyReusedException.class);
        verify(messageRepository, never()).save(any());
    }

    @Test
    void shouldRejectFreshInProgressDuplicate() {
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.RequestInProgress());

        AddTicketMessageCommand command = AddTicketMessageFixtures.employeeCommand(
            TICKET_ID, AddTicketMessageFixtures.employeeActor(AddTicketMessageFixtures.DEFAULT_REQUESTER), "same-key"
        );

        assertThatThrownBy(() -> service.addMessage(command)).isInstanceOf(RequestInProgressException.class);
        verify(messageRepository, never()).save(any());
    }
}
