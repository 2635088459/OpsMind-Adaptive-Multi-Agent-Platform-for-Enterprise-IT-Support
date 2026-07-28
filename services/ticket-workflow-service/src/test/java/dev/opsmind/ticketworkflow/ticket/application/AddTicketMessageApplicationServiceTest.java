package dev.opsmind.ticketworkflow.ticket.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.support.AddTicketMessageFixtures;
import dev.opsmind.ticketworkflow.ticket.application.command.AddTicketMessageCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.AddTicketMessageResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketMessageAddedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationOutcome;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.RequestHashCalculator;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.IdempotencyRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketMessageRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketMessageWriteGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.service.AddTicketMessageApplicationService;
import dev.opsmind.ticketworkflow.ticket.domain.message.MessageVisibility;
import dev.opsmind.ticketworkflow.ticket.domain.message.TicketMessage;
import dev.opsmind.ticketworkflow.ticket.domain.message.TicketMessageType;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
class AddTicketMessageApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-25T18:30:00Z");
    private static final UUID TICKET_ID = AddTicketMessageFixtures.DEFAULT_TICKET_ID;

    private TicketMessageRepository messageRepository;
    private TicketMessageWriteGuardPort writeGuardPort;
    private AuditRecordPort auditRecordPort;
    private OutboxEventRepository outboxEventRepository;
    private IdempotencyRepository idempotencyRepository;
    private ClockPort clock;
    private TicketTelemetry telemetry;
    private AddTicketMessageApplicationService service;

    @BeforeEach
    void setUp() {
        messageRepository = mock(TicketMessageRepository.class);
        writeGuardPort = mock(TicketMessageWriteGuardPort.class);
        auditRecordPort = mock(AuditRecordPort.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        idempotencyRepository = mock(IdempotencyRepository.class);
        clock = mock(ClockPort.class);
        telemetry = mock(TicketTelemetry.class);

        when(clock.now()).thenReturn(NOW);
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
    void shouldPersistMessageAuditAndOutboxForAnEmployeePublicMessage() {
        AddTicketMessageCommand command = AddTicketMessageFixtures.employeeCommand(
            TICKET_ID, AddTicketMessageFixtures.employeeActor("employee-123"), "key-1"
        );

        AddTicketMessageResult result = service.addMessage(command);

        assertThat(result.messageType()).isEqualTo(TicketMessageType.PUBLIC_REQUESTER_MESSAGE);
        assertThat(result.visibility()).isEqualTo(MessageVisibility.PUBLIC);
        assertThat(result.authorType()).isEqualTo("EMPLOYEE");
        assertThat(result.content()).isEqualTo(AddTicketMessageFixtures.DEFAULT_CONTENT);
        assertThat(result.version()).isZero();
        assertThat(result.idempotencyReplayed()).isFalse();

        ArgumentCaptor<TicketMessage> messageCaptor = ArgumentCaptor.forClass(TicketMessage.class);
        verify(messageRepository).save(messageCaptor.capture());
        assertThat(messageCaptor.getValue().id()).isEqualTo(result.messageId());

        ArgumentCaptor<AuditRecordEntry> auditCaptor = ArgumentCaptor.forClass(AuditRecordEntry.class);
        verify(auditRecordPort).append(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo("TICKET_MESSAGE_ADDED");
        assertThat(auditCaptor.getValue().resourceType()).isEqualTo("TICKET_MESSAGE");
        assertThat(auditCaptor.getValue().resourceId()).isEqualTo(result.messageId().toString());
        assertThat(auditCaptor.getValue().outcome()).isEqualTo("SUCCESS");

        ArgumentCaptor<OutboxEventEntry> outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntry.class);
        verify(outboxEventRepository).append(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().eventType()).isEqualTo("ticket.message.added");
        assertThat(outboxCaptor.getValue().routingKey()).isEqualTo("ticket.message.added.v1");
        assertThat(outboxCaptor.getValue().payload()).doesNotContainKey("content");

        verify(idempotencyRepository).complete(any(), any());
        verify(telemetry).recordMessageAdd("EMPLOYEE", TicketMessageType.PUBLIC_REQUESTER_MESSAGE, MessageVisibility.PUBLIC);
    }

    @Test
    void shouldUseInternalNoteAuditActionForInternalNotes() {
        AddTicketMessageCommand command = AddTicketMessageFixtures.supportCommand(
            TICKET_ID,
            AddTicketMessageFixtures.supportActor("support-100", AddTicketMessageFixtures.SUPPORT_INTERNAL_SCOPE),
            TicketMessageType.INTERNAL_SUPPORT_NOTE,
            java.util.Set.of(ApplicationCode.HOUSING_PORTAL),
            "key-2"
        );

        service.addMessage(command);

        ArgumentCaptor<AuditRecordEntry> auditCaptor = ArgumentCaptor.forClass(AuditRecordEntry.class);
        verify(auditRecordPort).append(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo("TICKET_INTERNAL_NOTE_ADDED");
    }
}
