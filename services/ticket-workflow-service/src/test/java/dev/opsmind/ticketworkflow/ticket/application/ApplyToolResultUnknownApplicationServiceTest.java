package dev.opsmind.ticketworkflow.ticket.application;

import dev.opsmind.ticketworkflow.ticket.application.command.ApplyToolResultUnknownCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyToolResultUnknownOutcome;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyToolResultUnknownResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketToolResultUnknownRecordedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.TicketStatusHistoryEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolExecutionExistingRecord;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolExecutionGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolResultUnknownGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolResultUnknownRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolResultUnknownUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolResultUnknownUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.application.service.ApplyToolResultUnknownApplicationService;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SPEC-TW-021: recorded-unknown/duplicate/stale/conflict-requires-reconciliation classification and the successful write transaction. */
@Tag("unit")
class ApplyToolResultUnknownApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-06T18:00:00Z");
    private static final Instant OBSERVED_AT = Instant.parse("2026-08-06T17:55:00Z");
    private static final UUID TICKET_ID = UUID.fromString("6c2ad02e-c394-41fb-8e38-dfffd581a59d");
    private static final UUID SUPPORT_QUEUE_ID = UUID.fromString("9d38b723-4a4d-47d3-94fe-32ef78cc0690");
    private static final UUID APPROVAL_REQUEST_ID = UUID.fromString("3d912886-9652-4d88-8a64-1297b50f14c7");
    private static final String ASSIGNEE_ID = "sam.support";

    private TicketToolResultUnknownGuardPort guardPort;
    private TicketToolResultUnknownRepository repository;
    private TicketHistoryWriter historyWriter;
    private AuditRecordPort auditRecordPort;
    private OutboxEventRepository outboxEventRepository;
    private ClockPort clock;
    private TicketTelemetry telemetry;
    private ApplyToolResultUnknownApplicationService service;

    @BeforeEach
    void setUp() {
        guardPort = mock(TicketToolResultUnknownGuardPort.class);
        repository = mock(TicketToolResultUnknownRepository.class);
        historyWriter = mock(TicketHistoryWriter.class);
        auditRecordPort = mock(AuditRecordPort.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        clock = mock(ClockPort.class);
        telemetry = mock(TicketTelemetry.class);

        when(clock.now()).thenReturn(NOW);
        when(repository.findExisting(any())).thenReturn(Optional.empty());
        when(guardPort.loadGuard(any(), any(), any())).thenReturn(Optional.of(executingGuard()));
        when(repository.recordUnknownResult(any())).thenAnswer(invocation -> {
            TicketToolResultUnknownUpdate update = invocation.getArgument(0);
            return new TicketToolResultUnknownUpdateOutcome.Applied(update.expectedVersion() + 1);
        });

        service = new ApplyToolResultUnknownApplicationService(
            guardPort, repository, historyWriter, auditRecordPort, outboxEventRepository, clock,
            new TicketToolResultUnknownRecordedEventMapper(), telemetry
        );
    }

    private TicketToolExecutionGuard executingGuard() {
        return guardInStatus(TicketStatus.EXECUTING, "wf-9000", "act-100", "RESET_MFA", "auth-5678");
    }

    private TicketToolExecutionGuard guardInStatus(TicketStatus ticketStatus, String workflowId, String actionId, String actionType, String authorizationReference) {
        return new TicketToolExecutionGuard(
            TicketId.of(TICKET_ID), TicketDisplayId.of("INC-42"), ticketStatus, 50L,
            SupportQueueId.of(SUPPORT_QUEUE_ID), ASSIGNEE_ID, APPROVAL_REQUEST_ID, actionType, authorizationReference
        );
    }

    private ApplyToolResultUnknownCommand command() {
        return new ApplyToolResultUnknownCommand(
            TicketId.of(TICKET_ID), "evt-unknown-1", "wf-9000", "act-100", "RESET_MFA", "auth-5678",
            "exec-500", "TIMEOUT_AFTER_REQUEST_SENT", List.of("log-ref-1"), OBSERVED_AT, "trace-1", "corr-1"
        );
    }

    @Test
    void shouldRecordUnknownResultAndEscalateTheTicket() {
        ApplyToolResultUnknownResult result = service.applyToolResultUnknown(command());

        assertThat(result.outcome()).isEqualTo(ApplyToolResultUnknownOutcome.RECORDED_UNKNOWN);
        assertThat(result.previousStatus()).isEqualTo(TicketStatus.EXECUTING);
        assertThat(result.status()).isEqualTo(TicketStatus.ESCALATED);
        assertThat(result.toolExecutionId()).isEqualTo("exec-500");
        assertThat(result.reconciliationRequired()).isTrue();
        assertThat(result.version()).isEqualTo(51L);

        ArgumentCaptor<TicketStatusHistoryEntry> historyCaptor = ArgumentCaptor.forClass(TicketStatusHistoryEntry.class);
        verify(historyWriter).append(historyCaptor.capture());
        assertThat(historyCaptor.getValue().toStatus()).isEqualTo(TicketStatus.ESCALATED);
        assertThat(historyCaptor.getValue().transitionId()).isEqualTo("SM-024");
        assertThat(historyCaptor.getValue().reasonCode()).isEqualTo("TOOL_RESULT_UNKNOWN");

        ArgumentCaptor<OutboxEventEntry> outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntry.class);
        verify(outboxEventRepository).append(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().eventType()).isEqualTo("ticket.tool-result-unknown-recorded");
        assertThat(outboxCaptor.getValue().payload()).containsEntry("newStatus", "ESCALATED");
        assertThat(outboxCaptor.getValue().payload()).containsEntry("reconciliationRequired", true);

        verify(telemetry).recordApplyToolResultUnknownOutcome("recorded_unknown");
    }

    @Test
    void shouldReturnDuplicateWhenTheToolExecutionIdIsAlreadyRecordedAsUnknown() {
        when(repository.findExisting("exec-500")).thenReturn(Optional.of(new TicketToolExecutionExistingRecord(TICKET_ID, "UNKNOWN")));

        ApplyToolResultUnknownResult result = service.applyToolResultUnknown(command());

        assertThat(result.outcome()).isEqualTo(ApplyToolResultUnknownOutcome.DUPLICATE);
        verify(guardPort, never()).loadGuard(any(), any(), any());
        verify(repository, never()).recordUnknownResult(any());
        verify(repository, never()).markConflictRequiresReconciliation(any(), any(), any());
        verify(historyWriter, never()).append(any());
        verify(outboxEventRepository, never()).append(any());
        verify(telemetry).recordApplyToolResultUnknownOutcome("duplicate");
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "FAILED"})
    void shouldFlagConflictRequiresReconciliationWhenATerminalOutcomeWasAlreadyRecorded(String existingStatus) {
        when(repository.findExisting("exec-500")).thenReturn(Optional.of(new TicketToolExecutionExistingRecord(TICKET_ID, existingStatus)));
        when(repository.markConflictRequiresReconciliation(any(), any(), any())).thenReturn(true);

        ApplyToolResultUnknownResult result = service.applyToolResultUnknown(command());

        assertThat(result.outcome()).isEqualTo(ApplyToolResultUnknownOutcome.CONFLICT_REQUIRES_RECONCILIATION);
        assertThat(result.reconciliationRequired()).isTrue();
        verify(repository).markConflictRequiresReconciliation(TicketId.of(TICKET_ID), "exec-500", "evt-unknown-1");
        verify(repository, never()).recordUnknownResult(any());
        verify(historyWriter, never()).append(any());
        verify(outboxEventRepository, never()).append(any());

        ArgumentCaptor<AuditRecordEntry> auditCaptor = ArgumentCaptor.forClass(AuditRecordEntry.class);
        verify(auditRecordPort).append(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo("TOOL_RESULT_UNKNOWN_CONFLICT_RECONCILIATION_REQUIRED");

        verify(telemetry).recordApplyToolResultUnknownOutcome("conflict_requires_reconciliation");
    }

    @Test
    void shouldReturnStaleWhenTheExistingRecordBelongsToADifferentTicket() {
        UUID otherTicketId = UUID.randomUUID();
        when(repository.findExisting("exec-500")).thenReturn(Optional.of(new TicketToolExecutionExistingRecord(otherTicketId, "COMPLETED")));

        ApplyToolResultUnknownResult result = service.applyToolResultUnknown(command());

        assertThat(result.outcome()).isEqualTo(ApplyToolResultUnknownOutcome.STALE);
        verify(repository, never()).markConflictRequiresReconciliation(any(), any(), any());
        verify(repository, never()).recordUnknownResult(any());
        verify(telemetry).recordApplyToolResultUnknownOutcome("stale");
    }

    @Test
    void shouldReturnStaleWhenNoAuthorizationMatches() {
        when(guardPort.loadGuard(any(), any(), any())).thenReturn(Optional.empty());

        ApplyToolResultUnknownResult result = service.applyToolResultUnknown(command());

        assertThat(result.outcome()).isEqualTo(ApplyToolResultUnknownOutcome.STALE);
        verify(repository, never()).recordUnknownResult(any());
        verify(telemetry).recordApplyToolResultUnknownOutcome("stale");
    }

    @Test
    void shouldReturnStaleWhenTheTicketHasMovedOffExecuting() {
        when(guardPort.loadGuard(any(), any(), any())).thenReturn(Optional.of(
            guardInStatus(TicketStatus.IN_PROGRESS, "wf-9000", "act-100", "RESET_MFA", "auth-5678")
        ));

        ApplyToolResultUnknownResult result = service.applyToolResultUnknown(command());

        assertThat(result.outcome()).isEqualTo(ApplyToolResultUnknownOutcome.STALE);
        verify(repository, never()).recordUnknownResult(any());
    }

    @Test
    void shouldReturnStaleWhenTheAuthorizationReferenceDoesNotMatch() {
        when(guardPort.loadGuard(any(), any(), any())).thenReturn(Optional.of(
            guardInStatus(TicketStatus.EXECUTING, "wf-9000", "act-100", "RESET_MFA", "auth-DIFFERENT")
        ));

        ApplyToolResultUnknownResult result = service.applyToolResultUnknown(command());

        assertThat(result.outcome()).isEqualTo(ApplyToolResultUnknownOutcome.STALE);
        verify(repository, never()).recordUnknownResult(any());
    }

    @Test
    void shouldReturnStaleWhenTheActionTypeDoesNotMatch() {
        when(guardPort.loadGuard(any(), any(), any())).thenReturn(Optional.of(
            guardInStatus(TicketStatus.EXECUTING, "wf-9000", "act-100", "DIFFERENT_TYPE", "auth-5678")
        ));

        ApplyToolResultUnknownResult result = service.applyToolResultUnknown(command());

        assertThat(result.outcome()).isEqualTo(ApplyToolResultUnknownOutcome.STALE);
        verify(repository, never()).recordUnknownResult(any());
    }

    @Test
    void shouldReturnStaleWhenTheWriteRaceIsDetectedAtThePersistenceLayer() {
        doReturn(new TicketToolResultUnknownUpdateOutcome.Conflict()).when(repository).recordUnknownResult(any());

        ApplyToolResultUnknownResult result = service.applyToolResultUnknown(command());

        assertThat(result.outcome()).isEqualTo(ApplyToolResultUnknownOutcome.STALE);
        verify(historyWriter, never()).append(any());
        verify(auditRecordPort, never()).append(any());
        verify(outboxEventRepository, never()).append(any());
    }
}
