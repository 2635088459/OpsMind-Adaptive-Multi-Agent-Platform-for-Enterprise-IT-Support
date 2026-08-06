package dev.opsmind.ticketworkflow.ticket.application;

import dev.opsmind.ticketworkflow.ticket.application.command.ApplyToolExecutionFailedCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyToolExecutionFailedOutcome;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyToolExecutionFailedResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketToolExecutionFailedAppliedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.TicketStatusHistoryEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolExecutionFailedRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolExecutionFailedUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolExecutionFailedUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolExecutionFailureGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolExecutionGuard;
import dev.opsmind.ticketworkflow.ticket.application.service.ApplyToolExecutionFailedApplicationService;
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

/** SPEC-TW-020: applied-safe/applied-pipeline/duplicate/stale classification and the successful write transaction. */
@Tag("unit")
class ApplyToolExecutionFailedApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-05T18:00:00Z");
    private static final Instant FAILED_AT = Instant.parse("2026-08-05T17:55:00Z");
    private static final UUID TICKET_ID = UUID.fromString("6c2ad02e-c394-41fb-8e38-dfffd581a59d");
    private static final UUID SUPPORT_QUEUE_ID = UUID.fromString("9d38b723-4a4d-47d3-94fe-32ef78cc0690");
    private static final UUID APPROVAL_REQUEST_ID = UUID.fromString("3d912886-9652-4d88-8a64-1297b50f14c7");
    private static final String ASSIGNEE_ID = "sam.support";

    private TicketToolExecutionFailureGuardPort guardPort;
    private TicketToolExecutionFailedRepository repository;
    private TicketHistoryWriter historyWriter;
    private AuditRecordPort auditRecordPort;
    private OutboxEventRepository outboxEventRepository;
    private ClockPort clock;
    private TicketTelemetry telemetry;
    private ApplyToolExecutionFailedApplicationService service;

    @BeforeEach
    void setUp() {
        guardPort = mock(TicketToolExecutionFailureGuardPort.class);
        repository = mock(TicketToolExecutionFailedRepository.class);
        historyWriter = mock(TicketHistoryWriter.class);
        auditRecordPort = mock(AuditRecordPort.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        clock = mock(ClockPort.class);
        telemetry = mock(TicketTelemetry.class);

        when(clock.now()).thenReturn(NOW);
        when(repository.existsByToolExecutionId(any())).thenReturn(false);
        when(guardPort.loadGuard(any(), any(), any())).thenReturn(Optional.of(executingGuard()));
        when(repository.applyToolExecutionFailed(any())).thenAnswer(invocation -> {
            TicketToolExecutionFailedUpdate update = invocation.getArgument(0);
            return new TicketToolExecutionFailedUpdateOutcome.Applied(update.expectedVersion() + 1);
        });

        service = new ApplyToolExecutionFailedApplicationService(
            guardPort, repository, historyWriter, auditRecordPort, outboxEventRepository, clock,
            new TicketToolExecutionFailedAppliedEventMapper(), telemetry
        );
    }

    private TicketToolExecutionGuard executingGuard() {
        return guardInStatus(TicketStatus.EXECUTING, "wf-9000", "act-100", "RESET_MFA", "auth-5678");
    }

    private TicketToolExecutionGuard guardInStatus(TicketStatus ticketStatus, String workflowId, String actionId, String actionType, String authorizationReference) {
        return new TicketToolExecutionGuard(
            TicketId.of(TICKET_ID), TicketDisplayId.of("INC-42"), ticketStatus, 40L,
            SupportQueueId.of(SUPPORT_QUEUE_ID), ASSIGNEE_ID, APPROVAL_REQUEST_ID, actionType, authorizationReference
        );
    }

    private ApplyToolExecutionFailedCommand command(String failureClass) {
        return new ApplyToolExecutionFailedCommand(
            TicketId.of(TICKET_ID), "evt-failed-1", "wf-9000", "act-100", "RESET_MFA", "auth-5678",
            "exec-500", "TARGET_ACCOUNT_NOT_FOUND", failureClass, FAILED_AT, false, "trace-1", "corr-1"
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"KNOWN_SAFE", "RETRYABLE_SAFE"})
    void shouldApplyASafeFailureAndReturnTheTicketToInProgress(String failureClass) {
        ApplyToolExecutionFailedResult result = service.applyToolExecutionFailed(command(failureClass));

        assertThat(result.outcome()).isEqualTo(ApplyToolExecutionFailedOutcome.APPLIED_SAFE_FAILURE);
        assertThat(result.previousStatus()).isEqualTo(TicketStatus.EXECUTING);
        assertThat(result.status()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(result.toolExecutionId()).isEqualTo("exec-500");
        assertThat(result.failureClass()).isEqualTo(failureClass);
        assertThat(result.version()).isEqualTo(41L);

        ArgumentCaptor<TicketStatusHistoryEntry> historyCaptor = ArgumentCaptor.forClass(TicketStatusHistoryEntry.class);
        verify(historyWriter).append(historyCaptor.capture());
        assertThat(historyCaptor.getValue().toStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(historyCaptor.getValue().transitionId()).isEqualTo("SM-022");
        assertThat(historyCaptor.getValue().reasonCode()).isEqualTo("TOOL_EXECUTION_FAILED_SAFE");

        ArgumentCaptor<OutboxEventEntry> outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntry.class);
        verify(outboxEventRepository).append(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().eventType()).isEqualTo("ticket.tool-execution-failed-applied");
        assertThat(outboxCaptor.getValue().payload()).containsEntry("newStatus", "IN_PROGRESS");

        verify(telemetry).recordApplyToolExecutionFailedOutcome("applied_safe_failure");
    }

    @Test
    void shouldApplyAPipelineFailureAndMoveTheTicketToFailed() {
        ApplyToolExecutionFailedResult result = service.applyToolExecutionFailed(command("PIPELINE_FAILED"));

        assertThat(result.outcome()).isEqualTo(ApplyToolExecutionFailedOutcome.APPLIED_PIPELINE_FAILURE);
        assertThat(result.status()).isEqualTo(TicketStatus.FAILED);

        ArgumentCaptor<TicketStatusHistoryEntry> historyCaptor = ArgumentCaptor.forClass(TicketStatusHistoryEntry.class);
        verify(historyWriter).append(historyCaptor.capture());
        assertThat(historyCaptor.getValue().toStatus()).isEqualTo(TicketStatus.FAILED);
        assertThat(historyCaptor.getValue().transitionId()).isEqualTo("SM-023");
        assertThat(historyCaptor.getValue().reasonCode()).isEqualTo("TOOL_EXECUTION_PIPELINE_FAILED");

        ArgumentCaptor<AuditRecordEntry> auditCaptor = ArgumentCaptor.forClass(AuditRecordEntry.class);
        verify(auditRecordPort).append(auditCaptor.capture());
        assertThat(auditCaptor.getValue().decision()).isEqualTo("ALLOWED");

        verify(telemetry).recordApplyToolExecutionFailedOutcome("applied_pipeline_failure");
    }

    @Test
    void shouldReturnDuplicateWhenTheToolExecutionIdIsAlreadyRecorded() {
        when(repository.existsByToolExecutionId("exec-500")).thenReturn(true);

        ApplyToolExecutionFailedResult result = service.applyToolExecutionFailed(command("KNOWN_SAFE"));

        assertThat(result.outcome()).isEqualTo(ApplyToolExecutionFailedOutcome.DUPLICATE);
        assertThat(result.toolExecutionId()).isEqualTo("exec-500");
        verify(guardPort, never()).loadGuard(any(), any(), any());
        verify(repository, never()).applyToolExecutionFailed(any());
        verify(historyWriter, never()).append(any());
        verify(outboxEventRepository, never()).append(any());
        verify(telemetry).recordApplyToolExecutionFailedOutcome("duplicate");
    }

    @Test
    void shouldReturnStaleWhenNoAuthorizationMatches() {
        when(guardPort.loadGuard(any(), any(), any())).thenReturn(Optional.empty());

        ApplyToolExecutionFailedResult result = service.applyToolExecutionFailed(command("KNOWN_SAFE"));

        assertThat(result.outcome()).isEqualTo(ApplyToolExecutionFailedOutcome.STALE);
        verify(repository, never()).applyToolExecutionFailed(any());
        verify(telemetry).recordApplyToolExecutionFailedOutcome("stale");
    }

    @Test
    void shouldReturnStaleWhenTheTicketHasMovedOffExecuting() {
        when(guardPort.loadGuard(any(), any(), any())).thenReturn(Optional.of(
            guardInStatus(TicketStatus.IN_PROGRESS, "wf-9000", "act-100", "RESET_MFA", "auth-5678")
        ));

        ApplyToolExecutionFailedResult result = service.applyToolExecutionFailed(command("KNOWN_SAFE"));

        assertThat(result.outcome()).isEqualTo(ApplyToolExecutionFailedOutcome.STALE);
        verify(repository, never()).applyToolExecutionFailed(any());
    }

    @Test
    void shouldReturnStaleWhenTheAuthorizationReferenceDoesNotMatch() {
        when(guardPort.loadGuard(any(), any(), any())).thenReturn(Optional.of(
            guardInStatus(TicketStatus.EXECUTING, "wf-9000", "act-100", "RESET_MFA", "auth-DIFFERENT")
        ));

        ApplyToolExecutionFailedResult result = service.applyToolExecutionFailed(command("KNOWN_SAFE"));

        assertThat(result.outcome()).isEqualTo(ApplyToolExecutionFailedOutcome.STALE);
        verify(repository, never()).applyToolExecutionFailed(any());
    }

    @Test
    void shouldReturnStaleWhenTheActionTypeDoesNotMatch() {
        when(guardPort.loadGuard(any(), any(), any())).thenReturn(Optional.of(
            guardInStatus(TicketStatus.EXECUTING, "wf-9000", "act-100", "DIFFERENT_TYPE", "auth-5678")
        ));

        ApplyToolExecutionFailedResult result = service.applyToolExecutionFailed(command("KNOWN_SAFE"));

        assertThat(result.outcome()).isEqualTo(ApplyToolExecutionFailedOutcome.STALE);
        verify(repository, never()).applyToolExecutionFailed(any());
    }

    @Test
    void shouldReturnStaleWhenTheWriteRaceIsDetectedAtThePersistenceLayer() {
        doReturn(new TicketToolExecutionFailedUpdateOutcome.Conflict()).when(repository).applyToolExecutionFailed(any());

        ApplyToolExecutionFailedResult result = service.applyToolExecutionFailed(command("KNOWN_SAFE"));

        assertThat(result.outcome()).isEqualTo(ApplyToolExecutionFailedOutcome.STALE);
        verify(historyWriter, never()).append(any());
        verify(auditRecordPort, never()).append(any());
        verify(outboxEventRepository, never()).append(any());
    }
}
