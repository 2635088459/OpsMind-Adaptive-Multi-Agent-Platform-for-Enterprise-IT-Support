package dev.opsmind.ticketworkflow.ticket.application;

import dev.opsmind.ticketworkflow.ticket.application.command.ApplyVerificationFailureCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyVerificationFailureOutcome;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyVerificationFailureResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketVerificationFailureAppliedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.TicketStatusHistoryEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketVerificationAttemptGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketVerificationFailureGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketVerificationFailureRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketVerificationFailureUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketVerificationFailureUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.application.service.ApplyVerificationFailureApplicationService;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SPEC-TW-024: retryable/escalated/pipeline-failed/duplicate/stale/conflict-requires-reconciliation classification and the successful write transaction. */
@Tag("unit")
class ApplyVerificationFailureApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-09T18:00:00Z");
    private static final Instant FAILED_AT = Instant.parse("2026-08-09T17:55:00Z");
    private static final UUID TICKET_ID = UUID.fromString("6c2ad02e-c394-41fb-8e38-dfffd581a59d");
    private static final UUID SUPPORT_QUEUE_ID = UUID.fromString("9d38b723-4a4d-47d3-94fe-32ef78cc0690");
    private static final UUID RESOLUTION_CYCLE_ID = UUID.fromString("3d912886-9652-4d88-8a64-1297b50f14c7");
    private static final String ASSIGNEE_ID = "sam.support";

    private TicketVerificationFailureGuardPort guardPort;
    private TicketVerificationFailureRepository repository;
    private TicketHistoryWriter historyWriter;
    private AuditRecordPort auditRecordPort;
    private OutboxEventRepository outboxEventRepository;
    private ClockPort clock;
    private TicketTelemetry telemetry;
    private ApplyVerificationFailureApplicationService service;

    @BeforeEach
    void setUp() {
        guardPort = mock(TicketVerificationFailureGuardPort.class);
        repository = mock(TicketVerificationFailureRepository.class);
        historyWriter = mock(TicketHistoryWriter.class);
        auditRecordPort = mock(AuditRecordPort.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        clock = mock(ClockPort.class);
        telemetry = mock(TicketTelemetry.class);

        when(clock.now()).thenReturn(NOW);
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(activeGuard()));
        when(repository.countFailedAttempts(any(), any())).thenReturn(0);
        when(repository.applyVerificationFailure(any())).thenAnswer(invocation -> {
            TicketVerificationFailureUpdate update = invocation.getArgument(0);
            return new TicketVerificationFailureUpdateOutcome.Applied(update.expectedVersion() + 1);
        });
        when(repository.markConflictRequiresReconciliation(any(), any(), any())).thenReturn(true);

        service = new ApplyVerificationFailureApplicationService(
            guardPort, repository, historyWriter, auditRecordPort, outboxEventRepository, clock,
            new TicketVerificationFailureAppliedEventMapper(), telemetry
        );
    }

    private TicketVerificationAttemptGuard activeGuard() {
        return guardInStatus(TicketStatus.VERIFYING, "ACTIVE", "wf-9000", RESOLUTION_CYCLE_ID, RESOLUTION_CYCLE_ID, 1);
    }

    private TicketVerificationAttemptGuard guardInStatus(
        TicketStatus ticketStatus, String attemptStatus, String attemptWorkflowId,
        UUID attemptResolutionCycleId, UUID currentResolutionCycleId, int attemptNumber
    ) {
        return new TicketVerificationAttemptGuard(
            TicketId.of(TICKET_ID), TicketDisplayId.of("INC-42"), ticketStatus, 62L,
            SupportQueueId.of(SUPPORT_QUEUE_ID), ASSIGNEE_ID, attemptWorkflowId, attemptResolutionCycleId,
            attemptNumber, attemptStatus, currentResolutionCycleId
        );
    }

    private ApplyVerificationFailureCommand command(String failureClass, boolean unsafeResult) {
        return new ApplyVerificationFailureCommand(
            TicketId.of(TICKET_ID), "evt-verification-failed-1", "ver-1234", "wf-9000", RESOLUTION_CYCLE_ID, 1,
            "LOGIN_STILL_FAILS", failureClass, unsafeResult, FAILED_AT, "trace-1", "corr-1"
        );
    }

    @Test
    void shouldApplyARetryableFailureAndReturnTheTicketToInProgress() {
        ApplyVerificationFailureResult result = service.applyVerificationFailure(command("RETRYABLE", false));

        assertThat(result.outcome()).isEqualTo(ApplyVerificationFailureOutcome.APPLIED_RETRYABLE);
        assertThat(result.previousStatus()).isEqualTo(TicketStatus.VERIFYING);
        assertThat(result.status()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(result.verificationId()).isEqualTo("ver-1234");
        assertThat(result.failureClass()).isEqualTo("RETRYABLE");
        assertThat(result.version()).isEqualTo(63L);

        ArgumentCaptor<TicketStatusHistoryEntry> historyCaptor = ArgumentCaptor.forClass(TicketStatusHistoryEntry.class);
        verify(historyWriter).append(historyCaptor.capture());
        assertThat(historyCaptor.getValue().toStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(historyCaptor.getValue().transitionId()).isEqualTo("SM-027");

        ArgumentCaptor<OutboxEventEntry> outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntry.class);
        verify(outboxEventRepository).append(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().eventType()).isEqualTo("ticket.verification-failure-applied");
        assertThat(outboxCaptor.getValue().payload()).containsEntry("newStatus", "IN_PROGRESS");

        verify(telemetry).recordApplyVerificationFailureOutcome("applied_retryable");
    }

    @Test
    void shouldEscalateWhenTheFailureLimitHasBeenReached() {
        when(repository.countFailedAttempts(any(), any())).thenReturn(2);

        ApplyVerificationFailureResult result = service.applyVerificationFailure(command("RETRYABLE", false));

        assertThat(result.outcome()).isEqualTo(ApplyVerificationFailureOutcome.APPLIED_ESCALATED);
        assertThat(result.status()).isEqualTo(TicketStatus.ESCALATED);
        verify(telemetry).recordApplyVerificationFailureOutcome("applied_escalated");
    }

    @Test
    void shouldEscalateOnAnUnsafeResultEvenUnderTheLimit() {
        ApplyVerificationFailureResult result = service.applyVerificationFailure(command("RETRYABLE", true));

        assertThat(result.outcome()).isEqualTo(ApplyVerificationFailureOutcome.APPLIED_ESCALATED);
        assertThat(result.status()).isEqualTo(TicketStatus.ESCALATED);
    }

    @Test
    void shouldApplyAPipelineFailureAndMoveTheTicketToFailed() {
        ApplyVerificationFailureResult result = service.applyVerificationFailure(command("PIPELINE_FAILED", false));

        assertThat(result.outcome()).isEqualTo(ApplyVerificationFailureOutcome.APPLIED_PIPELINE_FAILED);
        assertThat(result.status()).isEqualTo(TicketStatus.FAILED);

        ArgumentCaptor<AuditRecordEntry> auditCaptor = ArgumentCaptor.forClass(AuditRecordEntry.class);
        verify(auditRecordPort).append(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo("VERIFICATION_FAILURE_APPLIED");

        verify(telemetry).recordApplyVerificationFailureOutcome("applied_pipeline_failed");
    }

    @Test
    void shouldReturnStaleWhenNoAttemptMatches() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.empty());

        ApplyVerificationFailureResult result = service.applyVerificationFailure(command("RETRYABLE", false));

        assertThat(result.outcome()).isEqualTo(ApplyVerificationFailureOutcome.STALE);
        verify(repository, never()).applyVerificationFailure(any());
        verify(telemetry).recordApplyVerificationFailureOutcome("stale");
    }

    @Test
    void shouldReturnStaleWhenTheAttemptBelongsToADifferentTicket() {
        TicketVerificationAttemptGuard otherTicketGuard = new TicketVerificationAttemptGuard(
            TicketId.of(UUID.randomUUID()), TicketDisplayId.of("INC-99"), TicketStatus.VERIFYING, 5L,
            SupportQueueId.of(SUPPORT_QUEUE_ID), ASSIGNEE_ID, "wf-9000", RESOLUTION_CYCLE_ID, 1, "ACTIVE", RESOLUTION_CYCLE_ID
        );
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(otherTicketGuard));

        ApplyVerificationFailureResult result = service.applyVerificationFailure(command("RETRYABLE", false));

        assertThat(result.outcome()).isEqualTo(ApplyVerificationFailureOutcome.STALE);
        verify(repository, never()).applyVerificationFailure(any());
        verify(repository, never()).markConflictRequiresReconciliation(any(), any(), any());
    }

    @Test
    void shouldReturnDuplicateWhenTheAttemptIsAlreadyFailed() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(
            guardInStatus(TicketStatus.IN_PROGRESS, "FAILED", "wf-9000", RESOLUTION_CYCLE_ID, RESOLUTION_CYCLE_ID, 1)
        ));

        ApplyVerificationFailureResult result = service.applyVerificationFailure(command("RETRYABLE", false));

        assertThat(result.outcome()).isEqualTo(ApplyVerificationFailureOutcome.DUPLICATE);
        verify(repository, never()).applyVerificationFailure(any());
        verify(telemetry).recordApplyVerificationFailureOutcome("duplicate");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCEEDED", "STALE", "CONFLICT"})
    void shouldFlagConflictRequiresReconciliationWhenADifferentTerminalOutcomeWasAlreadyRecorded(String existingAttemptStatus) {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(
            guardInStatus(TicketStatus.VERIFYING, existingAttemptStatus, "wf-9000", RESOLUTION_CYCLE_ID, RESOLUTION_CYCLE_ID, 1)
        ));

        ApplyVerificationFailureResult result = service.applyVerificationFailure(command("RETRYABLE", false));

        assertThat(result.outcome()).isEqualTo(ApplyVerificationFailureOutcome.CONFLICT_REQUIRES_RECONCILIATION);
        verify(repository).markConflictRequiresReconciliation(TicketId.of(TICKET_ID), "ver-1234", "evt-verification-failed-1");
        verify(repository, never()).applyVerificationFailure(any());
        verify(historyWriter, never()).append(any());
        verify(outboxEventRepository, never()).append(any());
        verify(telemetry).recordApplyVerificationFailureOutcome("conflict_requires_reconciliation");
    }

    @Test
    void shouldReturnStaleWhenTheTicketHasMovedOffVerifying() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(
            guardInStatus(TicketStatus.ESCALATED, "ACTIVE", "wf-9000", RESOLUTION_CYCLE_ID, RESOLUTION_CYCLE_ID, 1)
        ));

        ApplyVerificationFailureResult result = service.applyVerificationFailure(command("RETRYABLE", false));

        assertThat(result.outcome()).isEqualTo(ApplyVerificationFailureOutcome.STALE);
        verify(repository, never()).applyVerificationFailure(any());
    }

    @Test
    void shouldReturnStaleWhenTheWorkflowIdDoesNotMatch() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(
            guardInStatus(TicketStatus.VERIFYING, "ACTIVE", "wf-DIFFERENT", RESOLUTION_CYCLE_ID, RESOLUTION_CYCLE_ID, 1)
        ));

        ApplyVerificationFailureResult result = service.applyVerificationFailure(command("RETRYABLE", false));

        assertThat(result.outcome()).isEqualTo(ApplyVerificationFailureOutcome.STALE);
        verify(repository, never()).applyVerificationFailure(any());
    }

    @Test
    void shouldReturnStaleWhenTheAttemptResolutionCycleDoesNotMatchTheEvent() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(
            guardInStatus(TicketStatus.VERIFYING, "ACTIVE", "wf-9000", UUID.randomUUID(), RESOLUTION_CYCLE_ID, 1)
        ));

        ApplyVerificationFailureResult result = service.applyVerificationFailure(command("RETRYABLE", false));

        assertThat(result.outcome()).isEqualTo(ApplyVerificationFailureOutcome.STALE);
        verify(repository, never()).applyVerificationFailure(any());
    }

    @Test
    void shouldReturnStaleWhenTheTicketsCurrentCycleHasMovedOn() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(
            guardInStatus(TicketStatus.VERIFYING, "ACTIVE", "wf-9000", RESOLUTION_CYCLE_ID, UUID.randomUUID(), 1)
        ));

        ApplyVerificationFailureResult result = service.applyVerificationFailure(command("RETRYABLE", false));

        assertThat(result.outcome()).isEqualTo(ApplyVerificationFailureOutcome.STALE);
        verify(repository, never()).applyVerificationFailure(any());
    }

    @Test
    void shouldReturnStaleWhenTheAttemptNumberDoesNotMatch() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(
            guardInStatus(TicketStatus.VERIFYING, "ACTIVE", "wf-9000", RESOLUTION_CYCLE_ID, RESOLUTION_CYCLE_ID, 2)
        ));

        ApplyVerificationFailureResult result = service.applyVerificationFailure(command("RETRYABLE", false));

        assertThat(result.outcome()).isEqualTo(ApplyVerificationFailureOutcome.STALE);
        verify(repository, never()).applyVerificationFailure(any());
    }

    @Test
    void shouldReturnStaleWhenTheWriteRaceIsDetectedAtThePersistenceLayer() {
        doReturn(new TicketVerificationFailureUpdateOutcome.Conflict()).when(repository).applyVerificationFailure(any());

        ApplyVerificationFailureResult result = service.applyVerificationFailure(command("RETRYABLE", false));

        assertThat(result.outcome()).isEqualTo(ApplyVerificationFailureOutcome.STALE);
        verify(historyWriter, never()).append(any());
        verify(auditRecordPort, never()).append(any());
        verify(outboxEventRepository, never()).append(any());
    }
}
