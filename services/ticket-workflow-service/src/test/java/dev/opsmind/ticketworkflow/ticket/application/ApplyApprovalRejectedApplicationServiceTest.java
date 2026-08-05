package dev.opsmind.ticketworkflow.ticket.application;

import dev.opsmind.ticketworkflow.ticket.application.command.ApplyApprovalRejectedCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyApprovalRejectedOutcome;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyApprovalRejectedResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketApprovalRejectedAppliedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.TicketStatusHistoryEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketApprovalRejectedRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketApprovalRejectedUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketApprovalRejectedUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketApprovalRejectionGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketApprovalRejectionGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.application.service.ApplyApprovalRejectedApplicationService;
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

/** SPEC-TW-016: applied/duplicate/stale/rejected-business-rule classification and the successful write transaction. */
@Tag("unit")
class ApplyApprovalRejectedApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-03T18:00:00Z");
    private static final Instant REQUESTED_AT = Instant.parse("2026-08-03T17:00:00Z");
    private static final UUID TICKET_ID = UUID.fromString("6c2ad02e-c394-41fb-8e38-dfffd581a59d");
    private static final UUID SUPPORT_QUEUE_ID = UUID.fromString("9d38b723-4a4d-47d3-94fe-32ef78cc0690");
    private static final UUID APPROVAL_REQUEST_ID = UUID.fromString("3d912886-9652-4d88-8a64-1297b50f14c7");
    private static final String ASSIGNEE_ID = "sam.support";
    private static final String APPROVAL_ID = "appr-1234";

    private TicketApprovalRejectionGuardPort guardPort;
    private TicketApprovalRejectedRepository repository;
    private TicketHistoryWriter historyWriter;
    private AuditRecordPort auditRecordPort;
    private OutboxEventRepository outboxEventRepository;
    private ClockPort clock;
    private TicketTelemetry telemetry;
    private ApplyApprovalRejectedApplicationService service;

    @BeforeEach
    void setUp() {
        guardPort = mock(TicketApprovalRejectionGuardPort.class);
        repository = mock(TicketApprovalRejectedRepository.class);
        historyWriter = mock(TicketHistoryWriter.class);
        auditRecordPort = mock(AuditRecordPort.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        clock = mock(ClockPort.class);
        telemetry = mock(TicketTelemetry.class);

        when(clock.now()).thenReturn(NOW);
        when(guardPort.loadGuard(any(), any())).thenReturn(Optional.of(openGuard()));
        when(repository.applyApprovalRejected(any())).thenAnswer(invocation -> {
            TicketApprovalRejectedUpdate update = invocation.getArgument(0);
            return new TicketApprovalRejectedUpdateOutcome.Applied(update.expectedVersion() + 1);
        });

        service = new ApplyApprovalRejectedApplicationService(
            guardPort, repository, historyWriter, auditRecordPort, outboxEventRepository, clock,
            new TicketApprovalRejectedAppliedEventMapper(), telemetry
        );
    }

    private TicketApprovalRejectionGuard openGuard() {
        return guardInStatus(TicketStatus.WAITING_FOR_APPROVAL, "OPEN", "wf-9000", "act-100", "RESET_MFA");
    }

    private TicketApprovalRejectionGuard guardInStatus(TicketStatus ticketStatus, String requestStatus, String workflowId, String actionId, String actionType) {
        return new TicketApprovalRejectionGuard(
            TicketId.of(TICKET_ID), TicketDisplayId.of("INC-42"), ticketStatus, 21L,
            SupportQueueId.of(SUPPORT_QUEUE_ID), ASSIGNEE_ID, APPROVAL_REQUEST_ID, requestStatus, workflowId, actionId, actionType, REQUESTED_AT
        );
    }

    private ApplyApprovalRejectedCommand command() {
        return command(Instant.parse("2026-08-03T17:50:00Z"));
    }

    private ApplyApprovalRejectedCommand command(Instant rejectedAt) {
        return new ApplyApprovalRejectedCommand(
            TicketId.of(TICKET_ID), "evt-1", "wf-9000", "act-100", APPROVAL_ID,
            "sha256:approver", rejectedAt, "INSUFFICIENT_JUSTIFICATION", "trace-1", "corr-1"
        );
    }

    @Test
    void shouldApplySuccessfullyAndPersistHistoryAuditAndOutbox() {
        ApplyApprovalRejectedResult result = service.applyApprovalRejected(command());

        assertThat(result.outcome()).isEqualTo(ApplyApprovalRejectedOutcome.APPLIED);
        assertThat(result.previousStatus()).isEqualTo(TicketStatus.WAITING_FOR_APPROVAL);
        assertThat(result.status()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(result.approvalRequestId()).isEqualTo(APPROVAL_REQUEST_ID);
        assertThat(result.version()).isEqualTo(22L);

        ArgumentCaptor<TicketStatusHistoryEntry> historyCaptor = ArgumentCaptor.forClass(TicketStatusHistoryEntry.class);
        verify(historyWriter).append(historyCaptor.capture());
        assertThat(historyCaptor.getValue().fromStatus()).isEqualTo(TicketStatus.WAITING_FOR_APPROVAL);
        assertThat(historyCaptor.getValue().toStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(historyCaptor.getValue().transitionId()).isEqualTo("SM-018");
        assertThat(historyCaptor.getValue().reasonCode()).isEqualTo("APPROVAL_REJECTED");

        ArgumentCaptor<AuditRecordEntry> auditCaptor = ArgumentCaptor.forClass(AuditRecordEntry.class);
        verify(auditRecordPort).append(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo("APPROVAL_REJECTED_APPLIED");
        assertThat(auditCaptor.getValue().actorType()).isEqualTo("SERVICE");

        ArgumentCaptor<OutboxEventEntry> outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntry.class);
        verify(outboxEventRepository).append(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().eventType()).isEqualTo("ticket.approval-rejected-applied");
        assertThat(outboxCaptor.getValue().routingKey()).isEqualTo("ticket.approval-rejected-applied.v1");
        assertThat(outboxCaptor.getValue().payload()).doesNotContainKey("rejectedBy");

        verify(telemetry).recordApplyApprovalRejectedOutcome("applied");
    }

    @Test
    void shouldReturnDuplicateWhenTheApprovalRequestIsAlreadyRejected() {
        when(guardPort.loadGuard(any(), any())).thenReturn(Optional.of(
            guardInStatus(TicketStatus.IN_PROGRESS, "REJECTED", "wf-9000", "act-100", "RESET_MFA")
        ));

        ApplyApprovalRejectedResult result = service.applyApprovalRejected(command());

        assertThat(result.outcome()).isEqualTo(ApplyApprovalRejectedOutcome.DUPLICATE);
        assertThat(result.approvalRequestId()).isEqualTo(APPROVAL_REQUEST_ID);
        verify(repository, never()).applyApprovalRejected(any());
        verify(historyWriter, never()).append(any());
        verify(outboxEventRepository, never()).append(any());
        verify(telemetry).recordApplyApprovalRejectedOutcome("duplicate");
    }

    @Test
    void shouldReturnStaleWhenNoApprovalRequestMatches() {
        when(guardPort.loadGuard(any(), any())).thenReturn(Optional.empty());

        ApplyApprovalRejectedResult result = service.applyApprovalRejected(command());

        assertThat(result.outcome()).isEqualTo(ApplyApprovalRejectedOutcome.STALE);
        assertThat(result.approvalRequestId()).isNull();
        verify(repository, never()).applyApprovalRejected(any());
        verify(telemetry).recordApplyApprovalRejectedOutcome("stale");
    }

    @ParameterizedTest
    @ValueSource(strings = {"GRANTED", "EXPIRED", "AUTO_APPROVED", "STALE"})
    void shouldReturnStaleWhenTheApprovalRequestIsInATerminalNonOpenState(String requestStatus) {
        when(guardPort.loadGuard(any(), any())).thenReturn(Optional.of(
            guardInStatus(TicketStatus.IN_PROGRESS, requestStatus, "wf-9000", "act-100", "RESET_MFA")
        ));

        ApplyApprovalRejectedResult result = service.applyApprovalRejected(command());

        assertThat(result.outcome()).isEqualTo(ApplyApprovalRejectedOutcome.STALE);
        verify(repository, never()).applyApprovalRejected(any());
    }

    @Test
    void shouldReturnStaleWhenTheTicketHasMovedOffWaitingForApproval() {
        when(guardPort.loadGuard(any(), any())).thenReturn(Optional.of(
            guardInStatus(TicketStatus.IN_PROGRESS, "OPEN", "wf-9000", "act-100", "RESET_MFA")
        ));

        ApplyApprovalRejectedResult result = service.applyApprovalRejected(command());

        assertThat(result.outcome()).isEqualTo(ApplyApprovalRejectedOutcome.STALE);
        verify(repository, never()).applyApprovalRejected(any());
    }

    @Test
    void shouldReturnStaleWhenTheWorkflowIdDoesNotMatch() {
        when(guardPort.loadGuard(any(), any())).thenReturn(Optional.of(
            guardInStatus(TicketStatus.WAITING_FOR_APPROVAL, "OPEN", "wf-DIFFERENT", "act-100", "RESET_MFA")
        ));

        ApplyApprovalRejectedResult result = service.applyApprovalRejected(command());

        assertThat(result.outcome()).isEqualTo(ApplyApprovalRejectedOutcome.STALE);
        verify(repository, never()).applyApprovalRejected(any());
    }

    @Test
    void shouldReturnStaleWhenTheActionIdDoesNotMatch() {
        when(guardPort.loadGuard(any(), any())).thenReturn(Optional.of(
            guardInStatus(TicketStatus.WAITING_FOR_APPROVAL, "OPEN", "wf-9000", "act-DIFFERENT", "RESET_MFA")
        ));

        ApplyApprovalRejectedResult result = service.applyApprovalRejected(command());

        assertThat(result.outcome()).isEqualTo(ApplyApprovalRejectedOutcome.STALE);
        verify(repository, never()).applyApprovalRejected(any());
    }

    @Test
    void shouldReturnRejectedBusinessRuleWhenRejectedAtPredatesTheRequest() {
        ApplyApprovalRejectedCommand backdatedCommand = command(Instant.parse("2026-08-03T16:00:00Z"));

        ApplyApprovalRejectedResult result = service.applyApprovalRejected(backdatedCommand);

        assertThat(result.outcome()).isEqualTo(ApplyApprovalRejectedOutcome.REJECTED_BUSINESS_RULE);
        assertThat(result.approvalRequestId()).isEqualTo(APPROVAL_REQUEST_ID);
        verify(repository, never()).applyApprovalRejected(any());
        verify(historyWriter, never()).append(any());
        verify(outboxEventRepository, never()).append(any());
        verify(telemetry).recordApplyApprovalRejectedOutcome("rejected_business_rule");
    }

    @Test
    void shouldReturnStaleWhenTheWriteRaceIsDetectedAtThePersistenceLayer() {
        org.mockito.Mockito.doReturn(new TicketApprovalRejectedUpdateOutcome.Conflict()).when(repository).applyApprovalRejected(any());

        ApplyApprovalRejectedResult result = service.applyApprovalRejected(command());

        assertThat(result.outcome()).isEqualTo(ApplyApprovalRejectedOutcome.STALE);
        verify(historyWriter, never()).append(any());
        verify(auditRecordPort, never()).append(any());
        verify(outboxEventRepository, never()).append(any());
    }
}
