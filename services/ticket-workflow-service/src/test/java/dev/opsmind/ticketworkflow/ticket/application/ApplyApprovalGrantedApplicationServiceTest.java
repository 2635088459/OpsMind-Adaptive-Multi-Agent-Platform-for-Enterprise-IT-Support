package dev.opsmind.ticketworkflow.ticket.application;

import dev.opsmind.ticketworkflow.ticket.application.command.ApplyApprovalGrantedCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyApprovalGrantedOutcome;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyApprovalGrantedResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketApprovalGrantedAppliedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.TicketStatusHistoryEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketApprovalGrantGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketApprovalGrantGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketApprovalGrantedRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketApprovalGrantedUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketApprovalGrantedUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.application.service.ApplyApprovalGrantedApplicationService;
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

/** SPEC-TW-015: applied/duplicate/stale/rejected-business-rule classification and the successful write transaction. */
@Tag("unit")
class ApplyApprovalGrantedApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-03T18:00:00Z");
    private static final UUID TICKET_ID = UUID.fromString("6c2ad02e-c394-41fb-8e38-dfffd581a59d");
    private static final UUID SUPPORT_QUEUE_ID = UUID.fromString("9d38b723-4a4d-47d3-94fe-32ef78cc0690");
    private static final UUID APPROVAL_REQUEST_ID = UUID.fromString("3d912886-9652-4d88-8a64-1297b50f14c7");
    private static final String ASSIGNEE_ID = "sam.support";
    private static final String APPROVAL_ID = "appr-1234";

    private TicketApprovalGrantGuardPort guardPort;
    private TicketApprovalGrantedRepository repository;
    private TicketHistoryWriter historyWriter;
    private AuditRecordPort auditRecordPort;
    private OutboxEventRepository outboxEventRepository;
    private ClockPort clock;
    private TicketTelemetry telemetry;
    private ApplyApprovalGrantedApplicationService service;

    @BeforeEach
    void setUp() {
        guardPort = mock(TicketApprovalGrantGuardPort.class);
        repository = mock(TicketApprovalGrantedRepository.class);
        historyWriter = mock(TicketHistoryWriter.class);
        auditRecordPort = mock(AuditRecordPort.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        clock = mock(ClockPort.class);
        telemetry = mock(TicketTelemetry.class);

        when(clock.now()).thenReturn(NOW);
        when(guardPort.loadGuard(any(), any())).thenReturn(Optional.of(openGuard()));
        when(repository.applyApprovalGranted(any())).thenAnswer(invocation -> {
            TicketApprovalGrantedUpdate update = invocation.getArgument(0);
            return new TicketApprovalGrantedUpdateOutcome.Applied(update.expectedVersion() + 1);
        });

        service = new ApplyApprovalGrantedApplicationService(
            guardPort, repository, historyWriter, auditRecordPort, outboxEventRepository, clock,
            new TicketApprovalGrantedAppliedEventMapper(), telemetry
        );
    }

    private TicketApprovalGrantGuard openGuard() {
        return guardInStatus(TicketStatus.WAITING_FOR_APPROVAL, "OPEN", "wf-9000", "act-100", "RESET_MFA");
    }

    private TicketApprovalGrantGuard guardInStatus(TicketStatus ticketStatus, String requestStatus, String workflowId, String actionId, String actionType) {
        return new TicketApprovalGrantGuard(
            TicketId.of(TICKET_ID), TicketDisplayId.of("INC-42"), ticketStatus, 21L,
            SupportQueueId.of(SUPPORT_QUEUE_ID), ASSIGNEE_ID, APPROVAL_REQUEST_ID, requestStatus, workflowId, actionId, actionType
        );
    }

    private ApplyApprovalGrantedCommand command() {
        return command(Instant.parse("2026-08-03T17:50:00Z"), Instant.parse("2026-08-03T18:30:00Z"));
    }

    private ApplyApprovalGrantedCommand command(Instant approvedAt, Instant expiresAt) {
        return new ApplyApprovalGrantedCommand(
            TicketId.of(TICKET_ID), "evt-1", "wf-9000", "act-100", "RESET_MFA", APPROVAL_ID,
            "sha256:approver", approvedAt, expiresAt, "trace-1", "corr-1"
        );
    }

    @Test
    void shouldApplySuccessfullyAndPersistHistoryAuditAndOutbox() {
        ApplyApprovalGrantedResult result = service.applyApprovalGranted(command());

        assertThat(result.outcome()).isEqualTo(ApplyApprovalGrantedOutcome.APPLIED);
        assertThat(result.previousStatus()).isEqualTo(TicketStatus.WAITING_FOR_APPROVAL);
        assertThat(result.status()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(result.approvalRequestId()).isEqualTo(APPROVAL_REQUEST_ID);
        assertThat(result.authorizationReference()).startsWith("auth-");
        assertThat(result.version()).isEqualTo(22L);

        ArgumentCaptor<TicketStatusHistoryEntry> historyCaptor = ArgumentCaptor.forClass(TicketStatusHistoryEntry.class);
        verify(historyWriter).append(historyCaptor.capture());
        assertThat(historyCaptor.getValue().fromStatus()).isEqualTo(TicketStatus.WAITING_FOR_APPROVAL);
        assertThat(historyCaptor.getValue().toStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(historyCaptor.getValue().transitionId()).isEqualTo("SM-017");
        assertThat(historyCaptor.getValue().reasonCode()).isEqualTo("APPROVAL_GRANTED");

        ArgumentCaptor<AuditRecordEntry> auditCaptor = ArgumentCaptor.forClass(AuditRecordEntry.class);
        verify(auditRecordPort).append(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo("APPROVAL_GRANTED_APPLIED");
        assertThat(auditCaptor.getValue().actorType()).isEqualTo("SERVICE");

        ArgumentCaptor<OutboxEventEntry> outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntry.class);
        verify(outboxEventRepository).append(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().eventType()).isEqualTo("ticket.approval-granted-applied");
        assertThat(outboxCaptor.getValue().routingKey()).isEqualTo("ticket.approval-granted-applied.v1");
        assertThat(outboxCaptor.getValue().payload()).doesNotContainKey("approvedBy");

        verify(telemetry).recordApplyApprovalGrantedOutcome("applied");
    }

    @Test
    void shouldReturnDuplicateWhenTheApprovalRequestIsAlreadyGranted() {
        when(guardPort.loadGuard(any(), any())).thenReturn(Optional.of(
            guardInStatus(TicketStatus.IN_PROGRESS, "GRANTED", "wf-9000", "act-100", "RESET_MFA")
        ));

        ApplyApprovalGrantedResult result = service.applyApprovalGranted(command());

        assertThat(result.outcome()).isEqualTo(ApplyApprovalGrantedOutcome.DUPLICATE);
        assertThat(result.approvalRequestId()).isEqualTo(APPROVAL_REQUEST_ID);
        verify(repository, never()).applyApprovalGranted(any());
        verify(historyWriter, never()).append(any());
        verify(outboxEventRepository, never()).append(any());
        verify(telemetry).recordApplyApprovalGrantedOutcome("duplicate");
    }

    @Test
    void shouldReturnStaleWhenNoApprovalRequestMatches() {
        when(guardPort.loadGuard(any(), any())).thenReturn(Optional.empty());

        ApplyApprovalGrantedResult result = service.applyApprovalGranted(command());

        assertThat(result.outcome()).isEqualTo(ApplyApprovalGrantedOutcome.STALE);
        assertThat(result.approvalRequestId()).isNull();
        verify(repository, never()).applyApprovalGranted(any());
        verify(telemetry).recordApplyApprovalGrantedOutcome("stale");
    }

    @ParameterizedTest
    @ValueSource(strings = {"REJECTED", "EXPIRED", "AUTO_APPROVED", "STALE"})
    void shouldReturnStaleWhenTheApprovalRequestIsInATerminalNonOpenState(String requestStatus) {
        when(guardPort.loadGuard(any(), any())).thenReturn(Optional.of(
            guardInStatus(TicketStatus.IN_PROGRESS, requestStatus, "wf-9000", "act-100", "RESET_MFA")
        ));

        ApplyApprovalGrantedResult result = service.applyApprovalGranted(command());

        assertThat(result.outcome()).isEqualTo(ApplyApprovalGrantedOutcome.STALE);
        verify(repository, never()).applyApprovalGranted(any());
    }

    @Test
    void shouldReturnStaleWhenTheTicketHasMovedOffWaitingForApproval() {
        when(guardPort.loadGuard(any(), any())).thenReturn(Optional.of(
            guardInStatus(TicketStatus.IN_PROGRESS, "OPEN", "wf-9000", "act-100", "RESET_MFA")
        ));

        ApplyApprovalGrantedResult result = service.applyApprovalGranted(command());

        assertThat(result.outcome()).isEqualTo(ApplyApprovalGrantedOutcome.STALE);
        verify(repository, never()).applyApprovalGranted(any());
    }

    @Test
    void shouldReturnStaleWhenTheWorkflowIdDoesNotMatch() {
        when(guardPort.loadGuard(any(), any())).thenReturn(Optional.of(
            guardInStatus(TicketStatus.WAITING_FOR_APPROVAL, "OPEN", "wf-DIFFERENT", "act-100", "RESET_MFA")
        ));

        ApplyApprovalGrantedResult result = service.applyApprovalGranted(command());

        assertThat(result.outcome()).isEqualTo(ApplyApprovalGrantedOutcome.STALE);
        verify(repository, never()).applyApprovalGranted(any());
    }

    @Test
    void shouldReturnStaleWhenTheActionIdDoesNotMatch() {
        when(guardPort.loadGuard(any(), any())).thenReturn(Optional.of(
            guardInStatus(TicketStatus.WAITING_FOR_APPROVAL, "OPEN", "wf-9000", "act-DIFFERENT", "RESET_MFA")
        ));

        ApplyApprovalGrantedResult result = service.applyApprovalGranted(command());

        assertThat(result.outcome()).isEqualTo(ApplyApprovalGrantedOutcome.STALE);
        verify(repository, never()).applyApprovalGranted(any());
    }

    @Test
    void shouldReturnStaleWhenTheActionTypeDoesNotMatch() {
        when(guardPort.loadGuard(any(), any())).thenReturn(Optional.of(
            guardInStatus(TicketStatus.WAITING_FOR_APPROVAL, "OPEN", "wf-9000", "act-100", "DIFFERENT_TYPE")
        ));

        ApplyApprovalGrantedResult result = service.applyApprovalGranted(command());

        assertThat(result.outcome()).isEqualTo(ApplyApprovalGrantedOutcome.STALE);
        verify(repository, never()).applyApprovalGranted(any());
    }

    @Test
    void shouldReturnRejectedBusinessRuleWhenApprovedAtIsAfterExpiresAt() {
        ApplyApprovalGrantedCommand expiredCommand = command(
            Instant.parse("2026-08-03T19:00:00Z"), Instant.parse("2026-08-03T18:00:00Z")
        );

        ApplyApprovalGrantedResult result = service.applyApprovalGranted(expiredCommand);

        assertThat(result.outcome()).isEqualTo(ApplyApprovalGrantedOutcome.REJECTED_BUSINESS_RULE);
        assertThat(result.approvalRequestId()).isEqualTo(APPROVAL_REQUEST_ID);
        verify(repository, never()).applyApprovalGranted(any());
        verify(historyWriter, never()).append(any());
        verify(outboxEventRepository, never()).append(any());
        verify(telemetry).recordApplyApprovalGrantedOutcome("rejected_business_rule");
    }

    @Test
    void shouldReturnStaleWhenTheWriteRaceIsDetectedAtThePersistenceLayer() {
        org.mockito.Mockito.doReturn(new TicketApprovalGrantedUpdateOutcome.Conflict()).when(repository).applyApprovalGranted(any());

        ApplyApprovalGrantedResult result = service.applyApprovalGranted(command());

        assertThat(result.outcome()).isEqualTo(ApplyApprovalGrantedOutcome.STALE);
        verify(historyWriter, never()).append(any());
        verify(auditRecordPort, never()).append(any());
        verify(outboxEventRepository, never()).append(any());
    }
}
