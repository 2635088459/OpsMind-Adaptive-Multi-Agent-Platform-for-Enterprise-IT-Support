package dev.opsmind.ticketworkflow.ticket.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.RequestUserInputCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.RequestUserInputResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketUserInputRequestedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.QueueAccessDeniedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.RequestInProgressException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketVersionConflictException;
import dev.opsmind.ticketworkflow.ticket.application.exception.UserInputRequestAlreadyOpenException;
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
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketStatusTransitionGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketStatusTransitionGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketUserInputRequestRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketUserInputRequestUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketUserInputRequestUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.application.service.RequestUserInputApplicationService;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidStatusTransitionException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.TicketNotAssignedException;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
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

/** SPEC-TW-012: the full success transaction, guard rejections, and idempotency outcomes. */
@Tag("unit")
class RequestUserInputApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-03T18:00:00Z");
    private static final UUID TICKET_ID = UUID.fromString("6c2ad02e-c394-41fb-8e38-dfffd581a59d");
    private static final UUID SUPPORT_QUEUE_ID = UUID.fromString("9d38b723-4a4d-47d3-94fe-32ef78cc0690");
    private static final String TEAM_ID = "team-endpoint";
    private static final String ASSIGNEE_ID = "sam.support";
    private static final String PROMPT = "Please upload a screenshot of the error and confirm whether the laptop is connected to VPN.";

    private TicketStatusTransitionGuardPort guardPort;
    private TicketUserInputRequestRepository requestRepository;
    private TicketHistoryWriter historyWriter;
    private AuditRecordPort auditRecordPort;
    private OutboxEventRepository outboxEventRepository;
    private IdempotencyRepository idempotencyRepository;
    private ClockPort clock;
    private TicketTelemetry telemetry;
    private RequestUserInputApplicationService service;

    @BeforeEach
    void setUp() {
        guardPort = mock(TicketStatusTransitionGuardPort.class);
        requestRepository = mock(TicketUserInputRequestRepository.class);
        historyWriter = mock(TicketHistoryWriter.class);
        auditRecordPort = mock(AuditRecordPort.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        idempotencyRepository = mock(IdempotencyRepository.class);
        clock = mock(ClockPort.class);
        telemetry = mock(TicketTelemetry.class);

        when(clock.now()).thenReturn(NOW);
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.Reserved(UUID.randomUUID()));
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(defaultGuard()));
        when(requestRepository.applyRequestUserInput(any())).thenAnswer(invocation -> {
            TicketUserInputRequestUpdate update = invocation.getArgument(0);
            return new TicketUserInputRequestUpdateOutcome.Created(update.expectedVersion() + 1);
        });

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new RequestUserInputApplicationService(
            guardPort, requestRepository, historyWriter, auditRecordPort, outboxEventRepository, idempotencyRepository,
            clock, new RequestHashCalculator(objectMapper), new TicketUserInputRequestedEventMapper(), telemetry, objectMapper
        );
    }

    private TicketStatusTransitionGuard defaultGuard() {
        return guardInStatus(TicketStatus.IN_PROGRESS, 20L);
    }

    private TicketStatusTransitionGuard guardInStatus(TicketStatus status, long version) {
        return new TicketStatusTransitionGuard(
            TicketId.of(TICKET_ID), TicketDisplayId.of("INC-42"), status, version,
            SupportQueueId.of(SUPPORT_QUEUE_ID), TEAM_ID, ASSIGNEE_ID
        );
    }

    private RequestUserInputCommand command(String idempotencyKey) {
        return new RequestUserInputCommand(
            TicketId.of(TICKET_ID), PROMPT, List.of("screenshot", "vpnStatus"), Instant.parse("2026-08-05T18:00:00Z"), 20L,
            new ActorContext("IT_SUPPORT", "sam.support", "support-console", Set.of("ticket:request-user-input")),
            Set.of(TEAM_ID), idempotencyKey, "corr-1", "cmd-1", NOW
        );
    }

    @Test
    void shouldRequestUserInputSuccessfullyAndPersistHistoryAuditOutboxAndIdempotency() {
        RequestUserInputResult result = service.requestUserInput(command("key-1"));

        assertThat(result.status()).isEqualTo(TicketStatus.WAITING_FOR_USER);
        assertThat(result.previousStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(result.prompt()).isEqualTo(PROMPT);
        assertThat(result.requestedBy()).isEqualTo("sam.support");
        assertThat(result.waitingForRequesterSince()).isEqualTo(NOW);
        assertThat(result.version()).isEqualTo(21L);
        assertThat(result.replayed()).isFalse();

        ArgumentCaptor<TicketStatusHistoryEntry> historyCaptor = ArgumentCaptor.forClass(TicketStatusHistoryEntry.class);
        verify(historyWriter).append(historyCaptor.capture());
        assertThat(historyCaptor.getValue().fromStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(historyCaptor.getValue().toStatus()).isEqualTo(TicketStatus.WAITING_FOR_USER);
        assertThat(historyCaptor.getValue().transitionId()).isEqualTo("SM-014");
        assertThat(historyCaptor.getValue().reasonCode()).isEqualTo("USER_INPUT_REQUIRED");
        assertThat(historyCaptor.getValue().aggregateVersion()).isEqualTo(21L);

        ArgumentCaptor<AuditRecordEntry> auditCaptor = ArgumentCaptor.forClass(AuditRecordEntry.class);
        verify(auditRecordPort).append(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo("USER_INPUT_REQUESTED");
        assertThat(auditCaptor.getValue().outcome()).isEqualTo("SUCCESS");

        ArgumentCaptor<OutboxEventEntry> outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntry.class);
        verify(outboxEventRepository).append(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().eventType()).isEqualTo("ticket.user-input-requested");
        assertThat(outboxCaptor.getValue().routingKey()).isEqualTo("ticket.user-input-requested.v1");

        verify(idempotencyRepository).complete(any(), any());
        verify(telemetry).recordRequestUserInputCommand("success");
    }

    @Test
    void shouldReturnOriginalResponseOnReplayWithoutAnySideEffects() {
        String storedJson = """
            {"ticketId":"%s","requestId":"3d912886-9652-4d88-8a64-1297b50f14c7","previousStatus":"IN_PROGRESS",\
            "status":"WAITING_FOR_USER","prompt":"%s","requestedBy":"sam.support",\
            "requestedAt":"2026-08-03T18:00:00Z","waitingForRequesterSince":"2026-08-03T18:00:00Z","version":21}
            """.formatted(TICKET_ID, PROMPT);
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.Replayed(201, storedJson));

        RequestUserInputResult result = service.requestUserInput(command("same-key"));

        assertThat(result.replayed()).isTrue();
        assertThat(result.version()).isEqualTo(21L);
        verify(guardPort, never()).loadGuard(any());
        verify(requestRepository, never()).applyRequestUserInput(any());
        verify(historyWriter, never()).append(any());
        verify(auditRecordPort, never()).append(any());
        verify(outboxEventRepository, never()).append(any());
        verify(telemetry).recordRequestUserInputCommand("replay");
    }

    @Test
    void shouldRejectSameIdempotencyKeyWithADifferentPayload() {
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.KeyReused());

        assertThatThrownBy(() -> service.requestUserInput(command("same-key"))).isInstanceOf(IdempotencyKeyReusedException.class);
        verify(requestRepository, never()).applyRequestUserInput(any());
    }

    @Test
    void shouldRejectAFreshInProgressDuplicate() {
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.RequestInProgress());

        assertThatThrownBy(() -> service.requestUserInput(command("same-key"))).isInstanceOf(RequestInProgressException.class);
        verify(requestRepository, never()).applyRequestUserInput(any());
    }

    @Test
    void shouldRejectAnActorWithoutTheRequiredScope() {
        RequestUserInputCommand command = new RequestUserInputCommand(
            TicketId.of(TICKET_ID), PROMPT, List.of(), null, 20L,
            new ActorContext("IT_SUPPORT", "sam.support", "support-console", Set.of()),
            Set.of(TEAM_ID), "key-1", "corr-1", "cmd-1", NOW
        );

        assertThatThrownBy(() -> service.requestUserInput(command)).isInstanceOf(TicketAuthorizationException.class);
        verify(idempotencyRepository, never()).reserve(any());
    }

    @Test
    void shouldRejectATicketOutsideTheActorsAuthorizedQueue() {
        RequestUserInputCommand command = new RequestUserInputCommand(
            TicketId.of(TICKET_ID), PROMPT, List.of(), null, 20L,
            new ActorContext("IT_SUPPORT", "sam.support", "support-console", Set.of("ticket:request-user-input")),
            Set.of("some-other-team"), "key-1", "corr-1", "cmd-1", NOW
        );

        assertThatThrownBy(() -> service.requestUserInput(command)).isInstanceOf(QueueAccessDeniedException.class);
        verify(requestRepository, never()).applyRequestUserInput(any());
    }

    @Test
    void shouldReturn404WhenTheTicketDoesNotExist() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requestUserInput(command("key-1"))).isInstanceOf(TicketNotFoundException.class);
    }

    @Test
    void shouldRejectAStaleExpectedVersion() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(guardInStatus(TicketStatus.IN_PROGRESS, 21L)));

        assertThatThrownBy(() -> service.requestUserInput(command("key-1")))
            .isInstanceOfSatisfying(TicketVersionConflictException.class, ex -> assertThat(ex.currentVersion()).isEqualTo(21L));
        verify(requestRepository, never()).applyRequestUserInput(any());
    }

    @Test
    void shouldRejectATicketAlreadyWaitingForUserWithTheSpecificErrorCode() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(guardInStatus(TicketStatus.WAITING_FOR_USER, 20L)));

        assertThatThrownBy(() -> service.requestUserInput(command("key-1"))).isInstanceOf(UserInputRequestAlreadyOpenException.class);
        verify(requestRepository, never()).applyRequestUserInput(any());
    }

    @Test
    void shouldRejectAnyOtherNonInProgressStatusWithInvalidStatusTransition() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(guardInStatus(TicketStatus.ASSIGNED, 20L)));

        assertThatThrownBy(() -> service.requestUserInput(command("key-1")))
            .isInstanceOfSatisfying(InvalidStatusTransitionException.class, ex -> {
                assertThat(ex.currentStatus()).isEqualTo(TicketStatus.ASSIGNED);
                assertThat(ex.targetStatus()).isEqualTo(TicketStatus.WAITING_FOR_USER);
            });
    }

    @Test
    void shouldRejectAnUnassignedTicketDetectedAtTheRepositoryLayer() {
        org.mockito.Mockito.doReturn(new TicketUserInputRequestUpdateOutcome.NotAssigned()).when(requestRepository).applyRequestUserInput(any());

        assertThatThrownBy(() -> service.requestUserInput(command("key-1"))).isInstanceOf(TicketNotAssignedException.class);
    }

    @Test
    void shouldRejectARequestAlreadyOpenRaceDetectedAtTheRepositoryLayer() {
        org.mockito.Mockito.doReturn(new TicketUserInputRequestUpdateOutcome.RequestAlreadyOpen()).when(requestRepository).applyRequestUserInput(any());

        assertThatThrownBy(() -> service.requestUserInput(command("key-1"))).isInstanceOf(UserInputRequestAlreadyOpenException.class);
    }
}
