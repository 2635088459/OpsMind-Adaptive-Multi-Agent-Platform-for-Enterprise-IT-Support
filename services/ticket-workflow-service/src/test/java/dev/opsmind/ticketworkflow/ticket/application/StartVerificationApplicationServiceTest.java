package dev.opsmind.ticketworkflow.ticket.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.StartVerificationCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.StartVerificationResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketVerificationStartedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.RequestInProgressException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketVersionConflictException;
import dev.opsmind.ticketworkflow.ticket.application.exception.VerificationAttemptAlreadyActiveException;
import dev.opsmind.ticketworkflow.ticket.application.exception.VerificationToolResultInvalidException;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationOutcome;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.RequestHashCalculator;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.TicketStatusHistoryEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.CompletedToolResultReference;
import dev.opsmind.ticketworkflow.ticket.application.port.out.IdempotencyRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketVerificationStartGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketVerificationStartGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketVerificationStartRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketVerificationStartUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketVerificationStartUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.application.service.StartVerificationApplicationService;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SPEC-TW-022: the full success transaction, guard rejections, and idempotency outcomes. */
@Tag("unit")
class StartVerificationApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-07T18:00:00Z");
    private static final UUID TICKET_ID = UUID.fromString("6c2ad02e-c394-41fb-8e38-dfffd581a59d");
    private static final UUID SUPPORT_QUEUE_ID = UUID.fromString("9d38b723-4a4d-47d3-94fe-32ef78cc0690");
    private static final UUID RESOLUTION_CYCLE_ID = UUID.fromString("3d912886-9652-4d88-8a64-1297b50f14c7");
    private static final String ASSIGNEE_ID = "sam.support";

    private TicketVerificationStartGuardPort guardPort;
    private TicketVerificationStartRepository repository;
    private TicketHistoryWriter historyWriter;
    private AuditRecordPort auditRecordPort;
    private OutboxEventRepository outboxEventRepository;
    private IdempotencyRepository idempotencyRepository;
    private ClockPort clock;
    private TicketTelemetry telemetry;
    private StartVerificationApplicationService service;

    @BeforeEach
    void setUp() {
        guardPort = mock(TicketVerificationStartGuardPort.class);
        repository = mock(TicketVerificationStartRepository.class);
        historyWriter = mock(TicketHistoryWriter.class);
        auditRecordPort = mock(AuditRecordPort.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        idempotencyRepository = mock(IdempotencyRepository.class);
        clock = mock(ClockPort.class);
        telemetry = mock(TicketTelemetry.class);

        when(clock.now()).thenReturn(NOW);
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.Reserved(UUID.randomUUID()));
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(defaultGuard()));
        when(repository.findCompletedToolResult(any(), any())).thenReturn(Optional.of(new CompletedToolResultReference("wf-9000")));
        when(repository.hasActiveAttempt(any())).thenReturn(false);
        when(repository.nextAttemptNumber(any())).thenReturn(1);
        when(repository.startVerification(any())).thenAnswer(invocation -> {
            TicketVerificationStartUpdate update = invocation.getArgument(0);
            return new TicketVerificationStartUpdateOutcome.Created(update.expectedVersion() + 1);
        });

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new StartVerificationApplicationService(
            guardPort, repository, historyWriter, auditRecordPort, outboxEventRepository, idempotencyRepository,
            clock, new RequestHashCalculator(objectMapper), new TicketVerificationStartedEventMapper(), telemetry, objectMapper
        );
    }

    private TicketVerificationStartGuard defaultGuard() {
        return guardInStatus(TicketStatus.VERIFYING, 60L, ASSIGNEE_ID);
    }

    private TicketVerificationStartGuard guardInStatus(TicketStatus status, long version, String assigneeId) {
        return new TicketVerificationStartGuard(
            TicketId.of(TICKET_ID), TicketDisplayId.of("INC-42"), status, version,
            SupportQueueId.of(SUPPORT_QUEUE_ID), assigneeId, RESOLUTION_CYCLE_ID
        );
    }

    private StartVerificationCommand command(String idempotencyKey) {
        return new StartVerificationCommand(
            TicketId.of(TICKET_ID), "tool-result-900", "IDENTITY_LOGIN_CHECK", "Confirm the requester can sign in.", 60L,
            new ActorContext("SERVICE", "verification-orchestrator", "verification-service", Set.of("ticket:verification-start")),
            idempotencyKey, "corr-1", "cmd-1", NOW
        );
    }

    @Test
    void shouldStartVerificationSuccessfullyAndPersistHistoryAuditOutboxAndIdempotency() {
        StartVerificationResult result = service.startVerification(command("key-1"));

        assertThat(result.status()).isEqualTo(TicketStatus.VERIFYING);
        assertThat(result.previousStatus()).isEqualTo(TicketStatus.VERIFYING);
        assertThat(result.workflowId()).isEqualTo("wf-9000");
        assertThat(result.toolResultId()).isEqualTo("tool-result-900");
        assertThat(result.resolutionCycleId()).isEqualTo(RESOLUTION_CYCLE_ID);
        assertThat(result.attemptNumber()).isEqualTo(1);
        assertThat(result.verificationType()).isEqualTo("IDENTITY_LOGIN_CHECK");
        assertThat(result.startedBy()).isEqualTo("verification-orchestrator");
        assertThat(result.verificationId()).startsWith("ver-");
        assertThat(result.version()).isEqualTo(61L);
        assertThat(result.replayed()).isFalse();

        ArgumentCaptor<TicketStatusHistoryEntry> historyCaptor = ArgumentCaptor.forClass(TicketStatusHistoryEntry.class);
        verify(historyWriter).append(historyCaptor.capture());
        assertThat(historyCaptor.getValue().fromStatus()).isEqualTo(TicketStatus.VERIFYING);
        assertThat(historyCaptor.getValue().toStatus()).isEqualTo(TicketStatus.VERIFYING);
        assertThat(historyCaptor.getValue().transitionId()).isEqualTo("SM-025");
        assertThat(historyCaptor.getValue().reasonCode()).isEqualTo("VERIFICATION_STARTED");
        assertThat(historyCaptor.getValue().aggregateVersion()).isEqualTo(61L);

        ArgumentCaptor<AuditRecordEntry> auditCaptor = ArgumentCaptor.forClass(AuditRecordEntry.class);
        verify(auditRecordPort).append(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo("VERIFICATION_STARTED");
        assertThat(auditCaptor.getValue().outcome()).isEqualTo("SUCCESS");

        ArgumentCaptor<OutboxEventEntry> outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntry.class);
        verify(outboxEventRepository).append(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().eventType()).isEqualTo("ticket.verification-started");
        assertThat(outboxCaptor.getValue().routingKey()).isEqualTo("ticket.verification-started.v1");
        assertThat(outboxCaptor.getValue().payload()).containsEntry("attemptNumber", 1);

        verify(idempotencyRepository).complete(any(), any());
        verify(telemetry).recordStartVerificationCommand("success");
    }

    @Test
    void shouldReturnOriginalResponseOnReplayWithoutAnySideEffects() {
        String storedJson = """
            {"ticketId":"%s","verificationId":"ver-1","resolutionCycleId":"%s","workflowId":"wf-9000",\
            "toolResultId":"tool-result-900","attemptNumber":1,"verificationType":"IDENTITY_LOGIN_CHECK",\
            "previousStatus":"VERIFYING","status":"VERIFYING","startedBy":"verification-orchestrator",\
            "startedAt":"2026-08-07T18:00:00Z","version":61}
            """.formatted(TICKET_ID, RESOLUTION_CYCLE_ID);
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.Replayed(201, storedJson));

        StartVerificationResult result = service.startVerification(command("same-key"));

        assertThat(result.replayed()).isTrue();
        assertThat(result.version()).isEqualTo(61L);
        verify(guardPort, never()).loadGuard(any());
        verify(repository, never()).startVerification(any());
        verify(historyWriter, never()).append(any());
        verify(auditRecordPort, never()).append(any());
        verify(outboxEventRepository, never()).append(any());
        verify(telemetry).recordStartVerificationCommand("replay");
    }

    @Test
    void shouldRejectSameIdempotencyKeyWithADifferentPayload() {
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.KeyReused());

        assertThatThrownBy(() -> service.startVerification(command("same-key"))).isInstanceOf(IdempotencyKeyReusedException.class);
        verify(repository, never()).startVerification(any());
    }

    @Test
    void shouldRejectAFreshInProgressDuplicate() {
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.RequestInProgress());

        assertThatThrownBy(() -> service.startVerification(command("same-key"))).isInstanceOf(RequestInProgressException.class);
        verify(repository, never()).startVerification(any());
    }

    @Test
    void shouldRejectAnActorWithoutTheRequiredScope() {
        StartVerificationCommand command = new StartVerificationCommand(
            TicketId.of(TICKET_ID), "tool-result-900", "IDENTITY_LOGIN_CHECK", null, 60L,
            new ActorContext("SERVICE", "verification-orchestrator", "verification-service", Set.of()),
            "key-1", "corr-1", "cmd-1", NOW
        );

        assertThatThrownBy(() -> service.startVerification(command)).isInstanceOf(TicketAuthorizationException.class);
        verify(idempotencyRepository, never()).reserve(any());
    }

    @Test
    void shouldRejectANonServiceActorEvenWithTheRequiredScope() {
        StartVerificationCommand command = new StartVerificationCommand(
            TicketId.of(TICKET_ID), "tool-result-900", "IDENTITY_LOGIN_CHECK", null, 60L,
            new ActorContext("IT_SUPPORT", "sam.support", "support-console", Set.of("ticket:verification-start")),
            "key-1", "corr-1", "cmd-1", NOW
        );

        assertThatThrownBy(() -> service.startVerification(command)).isInstanceOf(TicketAuthorizationException.class);
        verify(idempotencyRepository, never()).reserve(any());
    }

    @Test
    void shouldReturn404WhenTheTicketDoesNotExist() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.startVerification(command("key-1"))).isInstanceOf(TicketNotFoundException.class);
    }

    @Test
    void shouldRejectAStaleExpectedVersion() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(guardInStatus(TicketStatus.VERIFYING, 61L, ASSIGNEE_ID)));

        assertThatThrownBy(() -> service.startVerification(command("key-1")))
            .isInstanceOfSatisfying(TicketVersionConflictException.class, ex -> assertThat(ex.currentVersion()).isEqualTo(61L));
        verify(repository, never()).startVerification(any());
    }

    @Test
    void shouldRejectANonVerifyingStatus() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(guardInStatus(TicketStatus.EXECUTING, 60L, ASSIGNEE_ID)));

        assertThatThrownBy(() -> service.startVerification(command("key-1")))
            .isInstanceOfSatisfying(InvalidStatusTransitionException.class, ex -> {
                assertThat(ex.currentStatus()).isEqualTo(TicketStatus.EXECUTING);
                assertThat(ex.targetStatus()).isEqualTo(TicketStatus.VERIFYING);
            });
    }

    @Test
    void shouldRejectAnUnassignedTicket() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(guardInStatus(TicketStatus.VERIFYING, 60L, null)));

        assertThatThrownBy(() -> service.startVerification(command("key-1"))).isInstanceOf(TicketNotAssignedException.class);
        verify(repository, never()).startVerification(any());
    }

    @Test
    void shouldRejectAToolResultThatIsNotACompletedResultForThisTicket() {
        when(repository.findCompletedToolResult(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.startVerification(command("key-1"))).isInstanceOf(VerificationToolResultInvalidException.class);
        verify(repository, never()).startVerification(any());
    }

    @Test
    void shouldRejectAToolResultWithAnAlreadyActiveAttempt() {
        when(repository.hasActiveAttempt(any())).thenReturn(true);

        assertThatThrownBy(() -> service.startVerification(command("key-1"))).isInstanceOf(VerificationAttemptAlreadyActiveException.class);
        verify(repository, never()).startVerification(any());
    }

    @Test
    void shouldUseTheComputedAttemptNumber() {
        when(repository.nextAttemptNumber(any())).thenReturn(3);

        StartVerificationResult result = service.startVerification(command("key-1"));

        assertThat(result.attemptNumber()).isEqualTo(3);
    }

    @Test
    void shouldRejectAnUnassignedTicketDetectedAtTheRepositoryLayer() {
        doReturn(new TicketVerificationStartUpdateOutcome.NotAssigned()).when(repository).startVerification(any());

        assertThatThrownBy(() -> service.startVerification(command("key-1"))).isInstanceOf(TicketNotAssignedException.class);
    }

    @Test
    void shouldRejectAnAttemptAlreadyActiveRaceDetectedAtTheRepositoryLayer() {
        doReturn(new TicketVerificationStartUpdateOutcome.AttemptAlreadyActive()).when(repository).startVerification(any());

        assertThatThrownBy(() -> service.startVerification(command("key-1"))).isInstanceOf(VerificationAttemptAlreadyActiveException.class);
    }

    @Test
    void shouldRejectAVersionMismatchRaceDetectedAtTheRepositoryLayer() {
        doReturn(new TicketVerificationStartUpdateOutcome.VersionMismatch(99L)).when(repository).startVerification(any());

        assertThatThrownBy(() -> service.startVerification(command("key-1")))
            .isInstanceOfSatisfying(TicketVersionConflictException.class, ex -> assertThat(ex.currentVersion()).isEqualTo(99L));
    }
}
