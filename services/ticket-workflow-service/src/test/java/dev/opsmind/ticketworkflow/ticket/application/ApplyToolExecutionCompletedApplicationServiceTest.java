package dev.opsmind.ticketworkflow.ticket.application;

import dev.opsmind.ticketworkflow.ticket.application.command.ApplyToolExecutionCompletedCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyToolExecutionCompletedOutcome;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyToolExecutionCompletedResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketToolExecutionCompletedAppliedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.TicketStatusHistoryEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolExecutionCompletedRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolExecutionCompletedUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolExecutionCompletedUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolExecutionGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolExecutionGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.service.ApplyToolExecutionCompletedApplicationService;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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

/** SPEC-TW-019: applied/duplicate/stale classification and the successful write transaction. */
@Tag("unit")
class ApplyToolExecutionCompletedApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-04T18:00:00Z");
    private static final Instant COMPLETED_AT = Instant.parse("2026-08-04T17:55:00Z");
    private static final UUID TICKET_ID = UUID.fromString("6c2ad02e-c394-41fb-8e38-dfffd581a59d");
    private static final UUID SUPPORT_QUEUE_ID = UUID.fromString("9d38b723-4a4d-47d3-94fe-32ef78cc0690");
    private static final UUID APPROVAL_REQUEST_ID = UUID.fromString("3d912886-9652-4d88-8a64-1297b50f14c7");
    private static final String ASSIGNEE_ID = "sam.support";

    private TicketToolExecutionGuardPort guardPort;
    private TicketToolExecutionCompletedRepository repository;
    private TicketHistoryWriter historyWriter;
    private AuditRecordPort auditRecordPort;
    private OutboxEventRepository outboxEventRepository;
    private ClockPort clock;
    private TicketTelemetry telemetry;
    private ApplyToolExecutionCompletedApplicationService service;

    @BeforeEach
    void setUp() {
        guardPort = mock(TicketToolExecutionGuardPort.class);
        repository = mock(TicketToolExecutionCompletedRepository.class);
        historyWriter = mock(TicketHistoryWriter.class);
        auditRecordPort = mock(AuditRecordPort.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        clock = mock(ClockPort.class);
        telemetry = mock(TicketTelemetry.class);

        when(clock.now()).thenReturn(NOW);
        when(repository.existsByToolExecutionId(any())).thenReturn(false);
        when(guardPort.loadGuard(any(), any(), any())).thenReturn(Optional.of(executingGuard()));
        when(repository.applyToolExecutionCompleted(any())).thenAnswer(invocation -> {
            TicketToolExecutionCompletedUpdate update = invocation.getArgument(0);
            return new TicketToolExecutionCompletedUpdateOutcome.Applied(update.expectedVersion() + 1);
        });

        service = new ApplyToolExecutionCompletedApplicationService(
            guardPort, repository, historyWriter, auditRecordPort, outboxEventRepository, clock,
            new TicketToolExecutionCompletedAppliedEventMapper(), telemetry
        );
    }

    private TicketToolExecutionGuard executingGuard() {
        return guardInStatus(TicketStatus.EXECUTING, "wf-9000", "act-100", "RESET_MFA", "auth-5678");
    }

    private TicketToolExecutionGuard guardInStatus(TicketStatus ticketStatus, String workflowId, String actionId, String actionType, String authorizationReference) {
        return new TicketToolExecutionGuard(
            TicketId.of(TICKET_ID), TicketDisplayId.of("INC-42"), ticketStatus, 30L,
            SupportQueueId.of(SUPPORT_QUEUE_ID), ASSIGNEE_ID, APPROVAL_REQUEST_ID, actionType, authorizationReference
        );
    }

    private ApplyToolExecutionCompletedCommand command() {
        return new ApplyToolExecutionCompletedCommand(
            TicketId.of(TICKET_ID), "evt-completed-1", "wf-9000", "act-100", "RESET_MFA", "auth-5678",
            "exec-500", "result-900", COMPLETED_AT, Map.of("resultCode", "DUO_ENROLLMENT_RESET"), "trace-1", "corr-1"
        );
    }

    @Test
    void shouldApplySuccessfullyAndPersistHistoryAuditAndOutbox() {
        ApplyToolExecutionCompletedResult result = service.applyToolExecutionCompleted(command());

        assertThat(result.outcome()).isEqualTo(ApplyToolExecutionCompletedOutcome.APPLIED);
        assertThat(result.previousStatus()).isEqualTo(TicketStatus.EXECUTING);
        assertThat(result.status()).isEqualTo(TicketStatus.VERIFYING);
        assertThat(result.toolExecutionId()).isEqualTo("exec-500");
        assertThat(result.toolResultId()).isEqualTo("result-900");
        assertThat(result.version()).isEqualTo(31L);

        ArgumentCaptor<TicketStatusHistoryEntry> historyCaptor = ArgumentCaptor.forClass(TicketStatusHistoryEntry.class);
        verify(historyWriter).append(historyCaptor.capture());
        assertThat(historyCaptor.getValue().fromStatus()).isEqualTo(TicketStatus.EXECUTING);
        assertThat(historyCaptor.getValue().toStatus()).isEqualTo(TicketStatus.VERIFYING);
        assertThat(historyCaptor.getValue().transitionId()).isEqualTo("SM-021");
        assertThat(historyCaptor.getValue().reasonCode()).isEqualTo("TOOL_EXECUTION_COMPLETED");

        ArgumentCaptor<AuditRecordEntry> auditCaptor = ArgumentCaptor.forClass(AuditRecordEntry.class);
        verify(auditRecordPort).append(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo("TOOL_EXECUTION_COMPLETED_APPLIED");
        assertThat(auditCaptor.getValue().actorType()).isEqualTo("SERVICE");

        ArgumentCaptor<OutboxEventEntry> outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntry.class);
        verify(outboxEventRepository).append(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().eventType()).isEqualTo("ticket.tool-execution-completed-applied");
        assertThat(outboxCaptor.getValue().routingKey()).isEqualTo("ticket.tool-execution-completed-applied.v1");
        assertThat(outboxCaptor.getValue().payload()).containsEntry("toolExecutionId", "exec-500");

        verify(telemetry).recordApplyToolExecutionCompletedOutcome("applied");
    }

    @Test
    void shouldReturnDuplicateWhenTheToolExecutionIdIsAlreadyRecorded() {
        when(repository.existsByToolExecutionId("exec-500")).thenReturn(true);

        ApplyToolExecutionCompletedResult result = service.applyToolExecutionCompleted(command());

        assertThat(result.outcome()).isEqualTo(ApplyToolExecutionCompletedOutcome.DUPLICATE);
        assertThat(result.toolExecutionId()).isEqualTo("exec-500");
        verify(guardPort, never()).loadGuard(any(), any(), any());
        verify(repository, never()).applyToolExecutionCompleted(any());
        verify(historyWriter, never()).append(any());
        verify(outboxEventRepository, never()).append(any());
        verify(telemetry).recordApplyToolExecutionCompletedOutcome("duplicate");
    }

    @Test
    void shouldReturnStaleWhenNoAuthorizationMatches() {
        when(guardPort.loadGuard(any(), any(), any())).thenReturn(Optional.empty());

        ApplyToolExecutionCompletedResult result = service.applyToolExecutionCompleted(command());

        assertThat(result.outcome()).isEqualTo(ApplyToolExecutionCompletedOutcome.STALE);
        verify(repository, never()).applyToolExecutionCompleted(any());
        verify(telemetry).recordApplyToolExecutionCompletedOutcome("stale");
    }

    @Test
    void shouldReturnStaleWhenTheTicketHasMovedOffExecuting() {
        when(guardPort.loadGuard(any(), any(), any())).thenReturn(Optional.of(
            guardInStatus(TicketStatus.VERIFYING, "wf-9000", "act-100", "RESET_MFA", "auth-5678")
        ));

        ApplyToolExecutionCompletedResult result = service.applyToolExecutionCompleted(command());

        assertThat(result.outcome()).isEqualTo(ApplyToolExecutionCompletedOutcome.STALE);
        verify(repository, never()).applyToolExecutionCompleted(any());
    }

    @Test
    void shouldReturnStaleWhenTheAuthorizationReferenceDoesNotMatch() {
        when(guardPort.loadGuard(any(), any(), any())).thenReturn(Optional.of(
            guardInStatus(TicketStatus.EXECUTING, "wf-9000", "act-100", "RESET_MFA", "auth-DIFFERENT")
        ));

        ApplyToolExecutionCompletedResult result = service.applyToolExecutionCompleted(command());

        assertThat(result.outcome()).isEqualTo(ApplyToolExecutionCompletedOutcome.STALE);
        verify(repository, never()).applyToolExecutionCompleted(any());
    }

    @Test
    void shouldReturnStaleWhenTheActionTypeDoesNotMatch() {
        when(guardPort.loadGuard(any(), any(), any())).thenReturn(Optional.of(
            guardInStatus(TicketStatus.EXECUTING, "wf-9000", "act-100", "DIFFERENT_TYPE", "auth-5678")
        ));

        ApplyToolExecutionCompletedResult result = service.applyToolExecutionCompleted(command());

        assertThat(result.outcome()).isEqualTo(ApplyToolExecutionCompletedOutcome.STALE);
        verify(repository, never()).applyToolExecutionCompleted(any());
    }

    @Test
    void shouldReturnStaleWhenTheWriteRaceIsDetectedAtThePersistenceLayer() {
        doReturn(new TicketToolExecutionCompletedUpdateOutcome.Conflict()).when(repository).applyToolExecutionCompleted(any());

        ApplyToolExecutionCompletedResult result = service.applyToolExecutionCompleted(command());

        assertThat(result.outcome()).isEqualTo(ApplyToolExecutionCompletedOutcome.STALE);
        verify(historyWriter, never()).append(any());
        verify(auditRecordPort, never()).append(any());
        verify(outboxEventRepository, never()).append(any());
    }
}
