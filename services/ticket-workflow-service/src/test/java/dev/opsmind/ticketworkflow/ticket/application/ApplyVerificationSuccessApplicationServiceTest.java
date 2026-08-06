package dev.opsmind.ticketworkflow.ticket.application;

import dev.opsmind.ticketworkflow.ticket.application.command.ApplyVerificationSuccessCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyVerificationSuccessOutcome;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyVerificationSuccessResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketVerificationSuccessAppliedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.TicketStatusHistoryEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketVerificationAttemptGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketVerificationSuccessGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketVerificationSuccessRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketVerificationSuccessUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketVerificationSuccessUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.application.service.ApplyVerificationSuccessApplicationService;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SPEC-TW-023: applied/duplicate/stale/conflict-requires-reconciliation classification and the successful write transaction. */
@Tag("unit")
class ApplyVerificationSuccessApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-08T18:00:00Z");
    private static final Instant COMPLETED_AT = Instant.parse("2026-08-08T17:55:00Z");
    private static final UUID TICKET_ID = UUID.fromString("6c2ad02e-c394-41fb-8e38-dfffd581a59d");
    private static final UUID SUPPORT_QUEUE_ID = UUID.fromString("9d38b723-4a4d-47d3-94fe-32ef78cc0690");
    private static final UUID RESOLUTION_CYCLE_ID = UUID.fromString("3d912886-9652-4d88-8a64-1297b50f14c7");
    private static final String ASSIGNEE_ID = "sam.support";

    private TicketVerificationSuccessGuardPort guardPort;
    private TicketVerificationSuccessRepository repository;
    private TicketHistoryWriter historyWriter;
    private AuditRecordPort auditRecordPort;
    private OutboxEventRepository outboxEventRepository;
    private ClockPort clock;
    private TicketTelemetry telemetry;
    private ApplyVerificationSuccessApplicationService service;

    @BeforeEach
    void setUp() {
        guardPort = mock(TicketVerificationSuccessGuardPort.class);
        repository = mock(TicketVerificationSuccessRepository.class);
        historyWriter = mock(TicketHistoryWriter.class);
        auditRecordPort = mock(AuditRecordPort.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        clock = mock(ClockPort.class);
        telemetry = mock(TicketTelemetry.class);

        when(clock.now()).thenReturn(NOW);
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(activeGuard()));
        when(repository.applyVerificationSuccess(any())).thenAnswer(invocation -> {
            TicketVerificationSuccessUpdate update = invocation.getArgument(0);
            return new TicketVerificationSuccessUpdateOutcome.Applied(update.expectedVersion() + 1);
        });
        when(repository.markConflictRequiresReconciliation(any(), any(), any())).thenReturn(true);

        service = new ApplyVerificationSuccessApplicationService(
            guardPort, repository, historyWriter, auditRecordPort, outboxEventRepository, clock,
            new TicketVerificationSuccessAppliedEventMapper(), telemetry
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
            TicketId.of(TICKET_ID), TicketDisplayId.of("INC-42"), ticketStatus, 61L,
            SupportQueueId.of(SUPPORT_QUEUE_ID), ASSIGNEE_ID, attemptWorkflowId, attemptResolutionCycleId,
            attemptNumber, attemptStatus, currentResolutionCycleId
        );
    }

    private ApplyVerificationSuccessCommand command() {
        return new ApplyVerificationSuccessCommand(
            TicketId.of(TICKET_ID), "evt-verification-1", "ver-1234", "wf-9000", RESOLUTION_CYCLE_ID, 1,
            "evidence-900", Map.of("checkType", "LOGIN_TEST"), COMPLETED_AT, "trace-1", "corr-1"
        );
    }

    @Test
    void shouldApplySuccessfullyAndPersistHistoryAuditAndOutbox() {
        ApplyVerificationSuccessResult result = service.applyVerificationSuccess(command());

        assertThat(result.outcome()).isEqualTo(ApplyVerificationSuccessOutcome.APPLIED);
        assertThat(result.previousStatus()).isEqualTo(TicketStatus.VERIFYING);
        assertThat(result.status()).isEqualTo(TicketStatus.VERIFYING);
        assertThat(result.verificationId()).isEqualTo("ver-1234");
        assertThat(result.verificationEvidenceId()).isEqualTo("evidence-900");
        assertThat(result.version()).isEqualTo(62L);

        ArgumentCaptor<TicketStatusHistoryEntry> historyCaptor = ArgumentCaptor.forClass(TicketStatusHistoryEntry.class);
        verify(historyWriter).append(historyCaptor.capture());
        assertThat(historyCaptor.getValue().fromStatus()).isEqualTo(TicketStatus.VERIFYING);
        assertThat(historyCaptor.getValue().toStatus()).isEqualTo(TicketStatus.VERIFYING);
        assertThat(historyCaptor.getValue().transitionId()).isEqualTo("SM-026");
        assertThat(historyCaptor.getValue().reasonCode()).isEqualTo("VERIFICATION_SUCCEEDED");

        ArgumentCaptor<AuditRecordEntry> auditCaptor = ArgumentCaptor.forClass(AuditRecordEntry.class);
        verify(auditRecordPort).append(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo("VERIFICATION_SUCCESS_APPLIED");

        ArgumentCaptor<OutboxEventEntry> outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntry.class);
        verify(outboxEventRepository).append(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().eventType()).isEqualTo("ticket.verification-success-applied");
        assertThat(outboxCaptor.getValue().payload()).containsEntry("verificationEvidenceId", "evidence-900");

        verify(telemetry).recordApplyVerificationSuccessOutcome("applied");
    }

    @Test
    void shouldReturnStaleWhenNoAttemptMatches() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.empty());

        ApplyVerificationSuccessResult result = service.applyVerificationSuccess(command());

        assertThat(result.outcome()).isEqualTo(ApplyVerificationSuccessOutcome.STALE);
        verify(repository, never()).applyVerificationSuccess(any());
        verify(telemetry).recordApplyVerificationSuccessOutcome("stale");
    }

    @Test
    void shouldReturnStaleWhenTheAttemptBelongsToADifferentTicket() {
        TicketVerificationAttemptGuard otherTicketGuard = new TicketVerificationAttemptGuard(
            TicketId.of(UUID.randomUUID()), TicketDisplayId.of("INC-99"), TicketStatus.VERIFYING, 5L,
            SupportQueueId.of(SUPPORT_QUEUE_ID), ASSIGNEE_ID, "wf-9000", RESOLUTION_CYCLE_ID, 1, "ACTIVE", RESOLUTION_CYCLE_ID
        );
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(otherTicketGuard));

        ApplyVerificationSuccessResult result = service.applyVerificationSuccess(command());

        assertThat(result.outcome()).isEqualTo(ApplyVerificationSuccessOutcome.STALE);
        verify(repository, never()).applyVerificationSuccess(any());
        verify(repository, never()).markConflictRequiresReconciliation(any(), any(), any());
    }

    @Test
    void shouldReturnDuplicateWhenTheAttemptIsAlreadySucceeded() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(
            guardInStatus(TicketStatus.VERIFYING, "SUCCEEDED", "wf-9000", RESOLUTION_CYCLE_ID, RESOLUTION_CYCLE_ID, 1)
        ));

        ApplyVerificationSuccessResult result = service.applyVerificationSuccess(command());

        assertThat(result.outcome()).isEqualTo(ApplyVerificationSuccessOutcome.DUPLICATE);
        verify(repository, never()).applyVerificationSuccess(any());
        verify(telemetry).recordApplyVerificationSuccessOutcome("duplicate");
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "STALE", "CONFLICT"})
    void shouldFlagConflictRequiresReconciliationWhenADifferentTerminalOutcomeWasAlreadyRecorded(String existingAttemptStatus) {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(
            guardInStatus(TicketStatus.VERIFYING, existingAttemptStatus, "wf-9000", RESOLUTION_CYCLE_ID, RESOLUTION_CYCLE_ID, 1)
        ));

        ApplyVerificationSuccessResult result = service.applyVerificationSuccess(command());

        assertThat(result.outcome()).isEqualTo(ApplyVerificationSuccessOutcome.CONFLICT_REQUIRES_RECONCILIATION);
        verify(repository).markConflictRequiresReconciliation(TicketId.of(TICKET_ID), "ver-1234", "evt-verification-1");
        verify(repository, never()).applyVerificationSuccess(any());
        verify(historyWriter, never()).append(any());
        verify(outboxEventRepository, never()).append(any());
        verify(telemetry).recordApplyVerificationSuccessOutcome("conflict_requires_reconciliation");
    }

    @Test
    void shouldReturnStaleWhenTheTicketHasMovedOffVerifying() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(
            guardInStatus(TicketStatus.ESCALATED, "ACTIVE", "wf-9000", RESOLUTION_CYCLE_ID, RESOLUTION_CYCLE_ID, 1)
        ));

        ApplyVerificationSuccessResult result = service.applyVerificationSuccess(command());

        assertThat(result.outcome()).isEqualTo(ApplyVerificationSuccessOutcome.STALE);
        verify(repository, never()).applyVerificationSuccess(any());
    }

    @Test
    void shouldReturnStaleWhenTheWorkflowIdDoesNotMatch() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(
            guardInStatus(TicketStatus.VERIFYING, "ACTIVE", "wf-DIFFERENT", RESOLUTION_CYCLE_ID, RESOLUTION_CYCLE_ID, 1)
        ));

        ApplyVerificationSuccessResult result = service.applyVerificationSuccess(command());

        assertThat(result.outcome()).isEqualTo(ApplyVerificationSuccessOutcome.STALE);
        verify(repository, never()).applyVerificationSuccess(any());
    }

    @Test
    void shouldReturnStaleWhenTheAttemptResolutionCycleDoesNotMatchTheEvent() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(
            guardInStatus(TicketStatus.VERIFYING, "ACTIVE", "wf-9000", UUID.randomUUID(), RESOLUTION_CYCLE_ID, 1)
        ));

        ApplyVerificationSuccessResult result = service.applyVerificationSuccess(command());

        assertThat(result.outcome()).isEqualTo(ApplyVerificationSuccessOutcome.STALE);
        verify(repository, never()).applyVerificationSuccess(any());
    }

    @Test
    void shouldReturnStaleWhenTheTicketsCurrentCycleHasMovedOn() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(
            guardInStatus(TicketStatus.VERIFYING, "ACTIVE", "wf-9000", RESOLUTION_CYCLE_ID, UUID.randomUUID(), 1)
        ));

        ApplyVerificationSuccessResult result = service.applyVerificationSuccess(command());

        assertThat(result.outcome()).isEqualTo(ApplyVerificationSuccessOutcome.STALE);
        verify(repository, never()).applyVerificationSuccess(any());
    }

    @Test
    void shouldReturnStaleWhenTheAttemptNumberDoesNotMatch() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(
            guardInStatus(TicketStatus.VERIFYING, "ACTIVE", "wf-9000", RESOLUTION_CYCLE_ID, RESOLUTION_CYCLE_ID, 2)
        ));

        ApplyVerificationSuccessResult result = service.applyVerificationSuccess(command());

        assertThat(result.outcome()).isEqualTo(ApplyVerificationSuccessOutcome.STALE);
        verify(repository, never()).applyVerificationSuccess(any());
    }

    @Test
    void shouldReturnStaleWhenTheWriteRaceIsDetectedAtThePersistenceLayer() {
        doReturn(new TicketVerificationSuccessUpdateOutcome.Conflict()).when(repository).applyVerificationSuccess(any());

        ApplyVerificationSuccessResult result = service.applyVerificationSuccess(command());

        assertThat(result.outcome()).isEqualTo(ApplyVerificationSuccessOutcome.STALE);
        verify(historyWriter, never()).append(any());
        verify(auditRecordPort, never()).append(any());
        verify(outboxEventRepository, never()).append(any());
    }
}
