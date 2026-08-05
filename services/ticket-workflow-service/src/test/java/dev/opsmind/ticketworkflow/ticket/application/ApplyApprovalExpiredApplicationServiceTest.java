package dev.opsmind.ticketworkflow.ticket.application;

import dev.opsmind.ticketworkflow.ticket.application.command.ApplyApprovalExpiredCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyApprovalExpiredOutcome;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyApprovalExpiredResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketApprovalExpiredAppliedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.TicketStatusHistoryEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketApprovalExpirationGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketApprovalExpirationGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketApprovalExpiredRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketApprovalExpiredUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketApprovalExpiredUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.application.service.ApplyApprovalExpiredApplicationService;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SPEC-TW-017: applied/duplicate/stale/rejected-business-rule classification and the successful write transaction. */
@Tag("unit")
class ApplyApprovalExpiredApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-03T18:00:00Z");
    private static final UUID TICKET_ID = UUID.fromString("6c2ad02e-c394-41fb-8e38-dfffd581a59d");
    private static final UUID SUPPORT_QUEUE_ID = UUID.fromString("9d38b723-4a4d-47d3-94fe-32ef78cc0690");
    private static final UUID APPROVAL_REQUEST_ID = UUID.fromString("3d912886-9652-4d88-8a64-1297b50f14c7");
    private static final String ASSIGNEE_ID = "sam.support";
    private static final String APPROVAL_ID = "appr-1234";

    private TicketApprovalExpirationGuardPort guardPort;
    private TicketApprovalExpiredRepository repository;
    private TicketHistoryWriter historyWriter;
    private AuditRecordPort auditRecordPort;
    private OutboxEventRepository outboxEventRepository;
    private ClockPort clock;
    private TicketTelemetry telemetry;
    private ApplyApprovalExpiredApplicationService service;

    @BeforeEach
    void setUp() {
        guardPort = mock(TicketApprovalExpirationGuardPort.class);
        repository = mock(TicketApprovalExpiredRepository.class);
        historyWriter = mock(TicketHistoryWriter.class);
        auditRecordPort = mock(AuditRecordPort.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        clock = mock(ClockPort.class);
        telemetry = mock(TicketTelemetry.class);

        when(clock.now()).thenReturn(NOW);
        when(guardPort.loadGuard(any(), any())).thenReturn(Optional.of(openGuard(null)));
        when(repository.applyApprovalExpired(any())).thenAnswer(invocation -> {
            TicketApprovalExpiredUpdate update = invocation.getArgument(0);
            return new TicketApprovalExpiredUpdateOutcome.Applied(update.expectedVersion() + 1);
        });

        service = new ApplyApprovalExpiredApplicationService(
            guardPort, repository, historyWriter, auditRecordPort, outboxEventRepository, clock,
            new TicketApprovalExpiredAppliedEventMapper(), telemetry
        );
    }

    private TicketApprovalExpirationGuard openGuard(Instant expiresAt) {
        return guardInStatus(TicketStatus.WAITING_FOR_APPROVAL, "OPEN", "wf-9000", "act-100", "RESET_MFA", expiresAt);
    }

    private TicketApprovalExpirationGuard guardInStatus(
        TicketStatus ticketStatus, String requestStatus, String workflowId, String actionId, String actionType, Instant expiresAt
    ) {
        return new TicketApprovalExpirationGuard(
            TicketId.of(TICKET_ID), TicketDisplayId.of("INC-42"), ticketStatus, 21L,
            SupportQueueId.of(SUPPORT_QUEUE_ID), ASSIGNEE_ID, APPROVAL_REQUEST_ID, requestStatus, workflowId, actionId, actionType, expiresAt
        );
    }

    private ApplyApprovalExpiredCommand command() {
        return command(Instant.parse("2026-08-03T17:50:00Z"));
    }

    private ApplyApprovalExpiredCommand command(Instant expiredAt) {
        return new ApplyApprovalExpiredCommand(
            TicketId.of(TICKET_ID), "evt-1", "wf-9000", "act-100", APPROVAL_ID,
            expiredAt, "APPROVAL_SERVICE_TIMEOUT", "trace-1", "corr-1"
        );
    }

    @Test
    void shouldApplySuccessfullyAndPersistHistoryAuditAndOutbox() {
        ApplyApprovalExpiredResult result = service.applyApprovalExpired(command());

        assertThat(result.outcome()).isEqualTo(ApplyApprovalExpiredOutcome.APPLIED);
        assertThat(result.previousStatus()).isEqualTo(TicketStatus.WAITING_FOR_APPROVAL);
        assertThat(result.status()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(result.approvalRequestId()).isEqualTo(APPROVAL_REQUEST_ID);
        assertThat(result.version()).isEqualTo(22L);

        ArgumentCaptor<TicketStatusHistoryEntry> historyCaptor = ArgumentCaptor.forClass(TicketStatusHistoryEntry.class);
        verify(historyWriter).append(historyCaptor.capture());
        assertThat(historyCaptor.getValue().fromStatus()).isEqualTo(TicketStatus.WAITING_FOR_APPROVAL);
        assertThat(historyCaptor.getValue().toStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(historyCaptor.getValue().transitionId()).isEqualTo("SM-019");
        assertThat(historyCaptor.getValue().reasonCode()).isEqualTo("APPROVAL_EXPIRED");

        ArgumentCaptor<AuditRecordEntry> auditCaptor = ArgumentCaptor.forClass(AuditRecordEntry.class);
        verify(auditRecordPort).append(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo("APPROVAL_EXPIRED_APPLIED");
        assertThat(auditCaptor.getValue().actorType()).isEqualTo("SERVICE");

        ArgumentCaptor<OutboxEventEntry> outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntry.class);
        verify(outboxEventRepository).append(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().eventType()).isEqualTo("ticket.approval-expired-applied");
        assertThat(outboxCaptor.getValue().routingKey()).isEqualTo("ticket.approval-expired-applied.v1");
        assertThat(outboxCaptor.getValue().payload()).doesNotContainKey("expirationReason");

        verify(telemetry).recordApplyApprovalExpiredOutcome("applied");
    }

    @Test
    void shouldReturnDuplicateWhenTheApprovalRequestIsAlreadyExpired() {
        when(guardPort.loadGuard(any(), any())).thenReturn(Optional.of(
            guardInStatus(TicketStatus.IN_PROGRESS, "EXPIRED", "wf-9000", "act-100", "RESET_MFA", null)
        ));

        ApplyApprovalExpiredResult result = service.applyApprovalExpired(command());

        assertThat(result.outcome()).isEqualTo(ApplyApprovalExpiredOutcome.DUPLICATE);
        assertThat(result.approvalRequestId()).isEqualTo(APPROVAL_REQUEST_ID);
        verify(repository, never()).applyApprovalExpired(any());
        verify(historyWriter, never()).append(any());
        verify(outboxEventRepository, never()).append(any());
        verify(telemetry).recordApplyApprovalExpiredOutcome("duplicate");
    }

    @Test
    void shouldReturnStaleWhenNoApprovalRequestMatches() {
        when(guardPort.loadGuard(any(), any())).thenReturn(Optional.empty());

        ApplyApprovalExpiredResult result = service.applyApprovalExpired(command());

        assertThat(result.outcome()).isEqualTo(ApplyApprovalExpiredOutcome.STALE);
        assertThat(result.approvalRequestId()).isNull();
        verify(repository, never()).applyApprovalExpired(any());
        verify(telemetry).recordApplyApprovalExpiredOutcome("stale");
    }

    @ParameterizedTest
    @ValueSource(strings = {"GRANTED", "REJECTED", "AUTO_APPROVED", "STALE"})
    void shouldReturnStaleWhenTheApprovalWasAlreadyDecidedByACommittedTerminalState(String requestStatus) {
        when(guardPort.loadGuard(any(), any())).thenReturn(Optional.of(
            guardInStatus(TicketStatus.IN_PROGRESS, requestStatus, "wf-9000", "act-100", "RESET_MFA", null)
        ));

        ApplyApprovalExpiredResult result = service.applyApprovalExpired(command());

        assertThat(result.outcome()).isEqualTo(ApplyApprovalExpiredOutcome.STALE);
        verify(repository, never()).applyApprovalExpired(any());
    }

    @Test
    void shouldReturnStaleWhenTheTicketHasMovedOffWaitingForApproval() {
        when(guardPort.loadGuard(any(), any())).thenReturn(Optional.of(
            guardInStatus(TicketStatus.IN_PROGRESS, "OPEN", "wf-9000", "act-100", "RESET_MFA", null)
        ));

        ApplyApprovalExpiredResult result = service.applyApprovalExpired(command());

        assertThat(result.outcome()).isEqualTo(ApplyApprovalExpiredOutcome.STALE);
        verify(repository, never()).applyApprovalExpired(any());
    }

    @Test
    void shouldReturnStaleWhenTheWorkflowIdDoesNotMatch() {
        when(guardPort.loadGuard(any(), any())).thenReturn(Optional.of(
            guardInStatus(TicketStatus.WAITING_FOR_APPROVAL, "OPEN", "wf-DIFFERENT", "act-100", "RESET_MFA", null)
        ));

        ApplyApprovalExpiredResult result = service.applyApprovalExpired(command());

        assertThat(result.outcome()).isEqualTo(ApplyApprovalExpiredOutcome.STALE);
        verify(repository, never()).applyApprovalExpired(any());
    }

    @Test
    void shouldReturnStaleWhenTheActionIdDoesNotMatch() {
        when(guardPort.loadGuard(any(), any())).thenReturn(Optional.of(
            guardInStatus(TicketStatus.WAITING_FOR_APPROVAL, "OPEN", "wf-9000", "act-DIFFERENT", "RESET_MFA", null)
        ));

        ApplyApprovalExpiredResult result = service.applyApprovalExpired(command());

        assertThat(result.outcome()).isEqualTo(ApplyApprovalExpiredOutcome.STALE);
        verify(repository, never()).applyApprovalExpired(any());
    }

    @Test
    void shouldApplySuccessfullyWhenExpiredAtMatchesTheStoredExpiresAt() {
        Instant expiresAt = Instant.parse("2026-08-03T17:30:00Z");
        when(guardPort.loadGuard(any(), any())).thenReturn(Optional.of(openGuard(expiresAt)));

        ApplyApprovalExpiredResult result = service.applyApprovalExpired(command(expiresAt));

        assertThat(result.outcome()).isEqualTo(ApplyApprovalExpiredOutcome.APPLIED);
    }

    @Test
    void shouldReturnRejectedBusinessRuleWhenExpiredAtPredatesTheStoredExpiresAt() {
        Instant expiresAt = Instant.parse("2026-08-03T18:30:00Z");
        when(guardPort.loadGuard(any(), any())).thenReturn(Optional.of(openGuard(expiresAt)));
        ApplyApprovalExpiredCommand prematureCommand = command(Instant.parse("2026-08-03T18:00:00Z"));

        ApplyApprovalExpiredResult result = service.applyApprovalExpired(prematureCommand);

        assertThat(result.outcome()).isEqualTo(ApplyApprovalExpiredOutcome.REJECTED_BUSINESS_RULE);
        assertThat(result.approvalRequestId()).isEqualTo(APPROVAL_REQUEST_ID);
        verify(repository, never()).applyApprovalExpired(any());
        verify(historyWriter, never()).append(any());
        verify(outboxEventRepository, never()).append(any());
        verify(telemetry).recordApplyApprovalExpiredOutcome("rejected_business_rule");
    }

    @Test
    void shouldReturnStaleWhenTheWriteRaceIsDetectedAtThePersistenceLayer() {
        org.mockito.Mockito.doReturn(new TicketApprovalExpiredUpdateOutcome.Conflict()).when(repository).applyApprovalExpired(any());

        ApplyApprovalExpiredResult result = service.applyApprovalExpired(command());

        assertThat(result.outcome()).isEqualTo(ApplyApprovalExpiredOutcome.STALE);
        verify(historyWriter, never()).append(any());
        verify(auditRecordPort, never()).append(any());
        verify(outboxEventRepository, never()).append(any());
    }
}
