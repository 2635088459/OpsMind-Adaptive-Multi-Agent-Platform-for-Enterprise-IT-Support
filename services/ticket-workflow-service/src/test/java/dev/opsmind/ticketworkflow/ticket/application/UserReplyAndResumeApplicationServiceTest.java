package dev.opsmind.ticketworkflow.ticket.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.UserReplyAndResumeCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.UserReplyAndResumeResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketMessageAddedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketUserInputResumedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.RequestInProgressException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketVersionConflictException;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationOutcome;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.RequestHashCalculator;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.TicketStatusHistoryEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.IdempotencyRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketMessageRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketUserReplyGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketUserReplyGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketUserReplyRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketUserReplyResumeUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketUserReplyResumeUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.application.service.UserReplyAndResumeApplicationService;
import dev.opsmind.ticketworkflow.ticket.domain.message.MessageContent;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import dev.opsmind.ticketworkflow.ticket.domain.value.UserInputRequestStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SPEC-TW-013: the resume path, the soft (old/non-open-request) path, guard rejections, and idempotency outcomes. */
@Tag("unit")
class UserReplyAndResumeApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-03T19:15:00Z");
    private static final UUID TICKET_ID = UUID.fromString("6c2ad02e-c394-41fb-8e38-dfffd581a59d");
    private static final UUID REQUEST_ID = UUID.fromString("3d912886-9652-4d88-8a64-1297b50f14c7");
    private static final String REQUESTER_ID = "alice";
    private static final String BODY = "The laptop is connected to VPN and I attached the screenshot of the enrollment error.";

    private TicketUserReplyGuardPort guardPort;
    private TicketMessageRepository messageRepository;
    private TicketUserReplyRepository userReplyRepository;
    private TicketHistoryWriter historyWriter;
    private AuditRecordPort auditRecordPort;
    private OutboxEventRepository outboxEventRepository;
    private IdempotencyRepository idempotencyRepository;
    private ClockPort clock;
    private TicketTelemetry telemetry;
    private UserReplyAndResumeApplicationService service;

    @BeforeEach
    void setUp() {
        guardPort = mock(TicketUserReplyGuardPort.class);
        messageRepository = mock(TicketMessageRepository.class);
        userReplyRepository = mock(TicketUserReplyRepository.class);
        historyWriter = mock(TicketHistoryWriter.class);
        auditRecordPort = mock(AuditRecordPort.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        idempotencyRepository = mock(IdempotencyRepository.class);
        clock = mock(ClockPort.class);
        telemetry = mock(TicketTelemetry.class);

        when(clock.now()).thenReturn(NOW);
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.Reserved(UUID.randomUUID()));
        when(guardPort.loadGuard(any(), any())).thenReturn(Optional.of(openRequestGuard()));
        when(userReplyRepository.applyResume(any())).thenAnswer(invocation -> {
            TicketUserReplyResumeUpdate update = invocation.getArgument(0);
            return new TicketUserReplyResumeUpdateOutcome.Updated(update.expectedVersion() + 1);
        });

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new UserReplyAndResumeApplicationService(
            guardPort, messageRepository, userReplyRepository, historyWriter, auditRecordPort, outboxEventRepository,
            idempotencyRepository, clock, new RequestHashCalculator(objectMapper), new TicketMessageAddedEventMapper(),
            new TicketUserInputResumedEventMapper(), telemetry, objectMapper
        );
    }

    private TicketUserReplyGuard openRequestGuard() {
        return new TicketUserReplyGuard(
            TicketId.of(TICKET_ID), TicketDisplayId.of("INC-42"), REQUESTER_ID, TicketStatus.WAITING_FOR_USER, 21L,
            true, UserInputRequestStatus.OPEN
        );
    }

    private UserReplyAndResumeCommand command(String idempotencyKey) {
        return new UserReplyAndResumeCommand(
            TicketId.of(TICKET_ID), REQUEST_ID, MessageContent.of(BODY), List.of("att-001"), 21L,
            new ActorContext("EMPLOYEE", REQUESTER_ID, "employee-portal", Set.of("tickets:message:self")),
            idempotencyKey, "corr-1", "cmd-1", NOW
        );
    }

    @Test
    void shouldReplyAndResumeWhenTheRequestIsCurrentAndOpen() {
        UserReplyAndResumeResult result = service.reply(command("key-1"));

        assertThat(result.previousStatus()).isEqualTo(TicketStatus.WAITING_FOR_USER);
        assertThat(result.status()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(result.resumeApplied()).isTrue();
        assertThat(result.requestId()).isEqualTo(REQUEST_ID);
        assertThat(result.version()).isEqualTo(22L);
        assertThat(result.replayed()).isFalse();

        verify(messageRepository).save(any());

        ArgumentCaptor<TicketStatusHistoryEntry> historyCaptor = ArgumentCaptor.forClass(TicketStatusHistoryEntry.class);
        verify(historyWriter).append(historyCaptor.capture());
        assertThat(historyCaptor.getValue().transitionId()).isEqualTo("SM-015");
        assertThat(historyCaptor.getValue().reasonCode()).isEqualTo("USER_REPLIED");

        ArgumentCaptor<AuditRecordEntry> auditCaptor = ArgumentCaptor.forClass(AuditRecordEntry.class);
        verify(auditRecordPort).append(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo("USER_INPUT_RESUMED");

        ArgumentCaptor<OutboxEventEntry> outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntry.class);
        verify(outboxEventRepository, org.mockito.Mockito.times(2)).append(outboxCaptor.capture());
        List<String> eventTypes = outboxCaptor.getAllValues().stream().map(OutboxEventEntry::eventType).toList();
        assertThat(eventTypes).containsExactlyInAnyOrder("ticket.user-reply-received", "ticket.user-input-resumed");

        verify(idempotencyRepository).complete(any(), any());
        verify(telemetry).recordUserReplyCommand("success");
        verify(telemetry).recordUserReplyResumeApplied(true);
    }

    @Test
    void shouldSaveAsAPlainMessageWithoutResumingWhenTheTicketIsNotWaitingForUser() {
        when(guardPort.loadGuard(any(), any())).thenReturn(Optional.of(new TicketUserReplyGuard(
            TicketId.of(TICKET_ID), TicketDisplayId.of("INC-42"), REQUESTER_ID, TicketStatus.IN_PROGRESS, 21L,
            true, UserInputRequestStatus.ANSWERED
        )));

        UserReplyAndResumeResult result = service.reply(command("key-1"));

        assertThat(result.resumeApplied()).isFalse();
        assertThat(result.previousStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(result.status()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(result.version()).isEqualTo(21L);

        verify(messageRepository).save(any());
        verify(userReplyRepository, never()).applyResume(any());
        verify(historyWriter, never()).append(any());

        ArgumentCaptor<AuditRecordEntry> auditCaptor = ArgumentCaptor.forClass(AuditRecordEntry.class);
        verify(auditRecordPort).append(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo("TICKET_MESSAGE_ADDED");

        ArgumentCaptor<OutboxEventEntry> outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntry.class);
        verify(outboxEventRepository).append(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().eventType()).isEqualTo("ticket.message.added");

        verify(telemetry).recordUserReplyResumeApplied(false);
    }

    @Test
    void shouldSaveAsAPlainMessageWhenTheRequestIsAlreadyAnsweredEvenIfTicketIsWaitingAgain() {
        when(guardPort.loadGuard(any(), any())).thenReturn(Optional.of(new TicketUserReplyGuard(
            TicketId.of(TICKET_ID), TicketDisplayId.of("INC-42"), REQUESTER_ID, TicketStatus.WAITING_FOR_USER, 21L,
            true, UserInputRequestStatus.ANSWERED
        )));

        UserReplyAndResumeResult result = service.reply(command("key-1"));

        assertThat(result.resumeApplied()).isFalse();
        verify(userReplyRepository, never()).applyResume(any());
    }

    @Test
    void shouldReturnOriginalResponseOnReplayWithoutAnySideEffects() {
        String storedJson = """
            {"ticketId":"%s","requestId":"%s","messageId":"82fcd416-9138-42ce-b177-598a024c5c0f",\
            "previousStatus":"WAITING_FOR_USER","status":"IN_PROGRESS","answeredAt":"2026-08-03T19:15:00Z",\
            "resumeApplied":true,"version":22}
            """.formatted(TICKET_ID, REQUEST_ID);
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.Replayed(200, storedJson));

        UserReplyAndResumeResult result = service.reply(command("same-key"));

        assertThat(result.replayed()).isTrue();
        assertThat(result.resumeApplied()).isTrue();
        verify(guardPort, never()).loadGuard(any(), any());
        verify(messageRepository, never()).save(any());
        verify(userReplyRepository, never()).applyResume(any());
        verify(telemetry).recordUserReplyCommand("replay");
    }

    @Test
    void shouldRejectSameIdempotencyKeyWithADifferentPayload() {
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.KeyReused());

        assertThatThrownBy(() -> service.reply(command("same-key"))).isInstanceOf(IdempotencyKeyReusedException.class);
        verify(messageRepository, never()).save(any());
    }

    @Test
    void shouldRejectAFreshInProgressDuplicate() {
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.RequestInProgress());

        assertThatThrownBy(() -> service.reply(command("same-key"))).isInstanceOf(RequestInProgressException.class);
        verify(messageRepository, never()).save(any());
    }

    @Test
    void shouldRejectANonEmployeeActor() {
        UserReplyAndResumeCommand command = new UserReplyAndResumeCommand(
            TicketId.of(TICKET_ID), REQUEST_ID, MessageContent.of(BODY), List.of(), 21L,
            new ActorContext("IT_SUPPORT", "sam.support", "support-console", Set.of("tickets:message:self")),
            "key-1", "corr-1", "cmd-1", NOW
        );

        assertThatThrownBy(() -> service.reply(command)).isInstanceOf(TicketAuthorizationException.class);
        verify(idempotencyRepository, never()).reserve(any());
    }

    @Test
    void shouldRejectAnActorMissingTheRequiredScope() {
        UserReplyAndResumeCommand command = new UserReplyAndResumeCommand(
            TicketId.of(TICKET_ID), REQUEST_ID, MessageContent.of(BODY), List.of(), 21L,
            new ActorContext("EMPLOYEE", REQUESTER_ID, "employee-portal", Set.of()),
            "key-1", "corr-1", "cmd-1", NOW
        );

        assertThatThrownBy(() -> service.reply(command)).isInstanceOf(TicketAuthorizationException.class);
    }

    @Test
    void shouldReturn404WhenTheTicketDoesNotExist() {
        when(guardPort.loadGuard(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reply(command("key-1"))).isInstanceOf(TicketNotFoundException.class);
    }

    @Test
    void shouldReturn404WhenTheActorIsNotTheTicketsRequester() {
        when(guardPort.loadGuard(any(), any())).thenReturn(Optional.of(new TicketUserReplyGuard(
            TicketId.of(TICKET_ID), TicketDisplayId.of("INC-42"), "someone-else", TicketStatus.WAITING_FOR_USER, 21L,
            true, UserInputRequestStatus.OPEN
        )));

        assertThatThrownBy(() -> service.reply(command("key-1"))).isInstanceOf(TicketNotFoundException.class);
        verify(messageRepository, never()).save(any());
    }

    @Test
    void shouldReturn404WhenTheRequestDoesNotBelongToTheTicket() {
        when(guardPort.loadGuard(any(), any())).thenReturn(Optional.of(new TicketUserReplyGuard(
            TicketId.of(TICKET_ID), TicketDisplayId.of("INC-42"), REQUESTER_ID, TicketStatus.WAITING_FOR_USER, 21L,
            false, null
        )));

        assertThatThrownBy(() -> service.reply(command("key-1"))).isInstanceOf(TicketNotFoundException.class);
        verify(messageRepository, never()).save(any());
    }

    @Test
    void shouldRejectAStaleExpectedVersion() {
        when(guardPort.loadGuard(any(), any())).thenReturn(Optional.of(new TicketUserReplyGuard(
            TicketId.of(TICKET_ID), TicketDisplayId.of("INC-42"), REQUESTER_ID, TicketStatus.WAITING_FOR_USER, 22L,
            true, UserInputRequestStatus.OPEN
        )));

        assertThatThrownBy(() -> service.reply(command("key-1")))
            .isInstanceOfSatisfying(TicketVersionConflictException.class, ex -> assertThat(ex.currentVersion()).isEqualTo(22L));
        verify(messageRepository, never()).save(any());
    }
}
