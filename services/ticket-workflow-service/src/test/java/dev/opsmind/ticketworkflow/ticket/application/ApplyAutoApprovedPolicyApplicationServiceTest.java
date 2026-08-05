package dev.opsmind.ticketworkflow.ticket.application;

import dev.opsmind.ticketworkflow.ticket.application.command.ApplyAutoApprovedPolicyCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyAutoApprovedPolicyOutcome;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyAutoApprovedPolicyResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketAutoApprovalAppliedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.TicketStatusHistoryEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAutoApprovedPolicyGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAutoApprovedPolicyGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAutoApprovedPolicyInsert;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAutoApprovedPolicyInsertOutcome;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAutoApprovedPolicyRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.application.service.ApplyAutoApprovedPolicyApplicationService;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApprovalRiskLevel;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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

/** SPEC-TW-018: applied/duplicate/stale/rejected-business-rule classification and the successful write transaction. */
@Tag("unit")
class ApplyAutoApprovedPolicyApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-03T18:00:00Z");
    private static final UUID TICKET_ID = UUID.fromString("6c2ad02e-c394-41fb-8e38-dfffd581a59d");
    private static final UUID SUPPORT_QUEUE_ID = UUID.fromString("9d38b723-4a4d-47d3-94fe-32ef78cc0690");
    private static final UUID EXISTING_APPROVAL_REQUEST_ID = UUID.fromString("3d912886-9652-4d88-8a64-1297b50f14c7");
    private static final String ASSIGNEE_ID = "sam.support";
    private static final String POLICY_DECISION_ID = "policy-dec-300";

    private TicketAutoApprovedPolicyGuardPort guardPort;
    private TicketAutoApprovedPolicyRepository repository;
    private TicketHistoryWriter historyWriter;
    private AuditRecordPort auditRecordPort;
    private OutboxEventRepository outboxEventRepository;
    private ClockPort clock;
    private TicketTelemetry telemetry;
    private ApplyAutoApprovedPolicyApplicationService service;

    @BeforeEach
    void setUp() {
        guardPort = mock(TicketAutoApprovedPolicyGuardPort.class);
        repository = mock(TicketAutoApprovedPolicyRepository.class);
        historyWriter = mock(TicketHistoryWriter.class);
        auditRecordPort = mock(AuditRecordPort.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        clock = mock(ClockPort.class);
        telemetry = mock(TicketTelemetry.class);

        when(clock.now()).thenReturn(NOW);
        when(guardPort.loadGuard(any(), any())).thenReturn(Optional.of(eligibleGuard()));
        when(repository.applyAutoApprovedPolicy(any())).thenAnswer(invocation -> {
            TicketAutoApprovedPolicyInsert insert = invocation.getArgument(0);
            return new TicketAutoApprovedPolicyInsertOutcome.Applied(insert.expectedVersion() + 1);
        });

        service = new ApplyAutoApprovedPolicyApplicationService(
            guardPort, repository, historyWriter, auditRecordPort, outboxEventRepository, clock,
            new TicketAutoApprovalAppliedEventMapper(), telemetry
        );
    }

    private TicketAutoApprovedPolicyGuard eligibleGuard() {
        return guardInStatus(TicketStatus.IN_PROGRESS, null);
    }

    private TicketAutoApprovedPolicyGuard guardInStatus(TicketStatus ticketStatus, UUID existingApprovalRequestId) {
        return new TicketAutoApprovedPolicyGuard(
            TicketId.of(TICKET_ID), TicketDisplayId.of("INC-42"), ticketStatus, 21L,
            SupportQueueId.of(SUPPORT_QUEUE_ID), ASSIGNEE_ID, existingApprovalRequestId
        );
    }

    private ApplyAutoApprovedPolicyCommand command() {
        return command(ApprovalRiskLevel.LOW);
    }

    private ApplyAutoApprovedPolicyCommand command(ApprovalRiskLevel riskLevel) {
        return new ApplyAutoApprovedPolicyCommand(
            TicketId.of(TICKET_ID), "evt-1", "wf-9000", "act-100", "REFRESH_USER_SESSION", riskLevel,
            "policy-42", "1.0", POLICY_DECISION_ID, Instant.parse("2026-08-03T17:50:00Z"), "trace-1", "corr-1"
        );
    }

    @Test
    void shouldApplySuccessfullyAndPersistHistoryAuditAndOutbox() {
        ApplyAutoApprovedPolicyResult result = service.applyAutoApprovedPolicy(command());

        assertThat(result.outcome()).isEqualTo(ApplyAutoApprovedPolicyOutcome.APPLIED);
        assertThat(result.previousStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(result.status()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(result.authorizationReference()).startsWith("auth-");
        assertThat(result.version()).isEqualTo(22L);

        ArgumentCaptor<TicketStatusHistoryEntry> historyCaptor = ArgumentCaptor.forClass(TicketStatusHistoryEntry.class);
        verify(historyWriter).append(historyCaptor.capture());
        assertThat(historyCaptor.getValue().fromStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(historyCaptor.getValue().toStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(historyCaptor.getValue().transitionId()).isEqualTo("SM-020");
        assertThat(historyCaptor.getValue().reasonCode()).isEqualTo("AUTO_APPROVAL_APPLIED");

        ArgumentCaptor<AuditRecordEntry> auditCaptor = ArgumentCaptor.forClass(AuditRecordEntry.class);
        verify(auditRecordPort).append(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo("AUTO_APPROVAL_APPLIED");
        assertThat(auditCaptor.getValue().actorType()).isEqualTo("SERVICE");

        ArgumentCaptor<OutboxEventEntry> outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntry.class);
        verify(outboxEventRepository).append(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().eventType()).isEqualTo("ticket.auto-approval-applied");
        assertThat(outboxCaptor.getValue().routingKey()).isEqualTo("ticket.auto-approval-applied.v1");
        assertThat(outboxCaptor.getValue().payload()).containsEntry("policyDecisionId", POLICY_DECISION_ID);

        verify(telemetry).recordApplyAutoApprovedPolicyOutcome("applied");
    }

    @Test
    void shouldReturnDuplicateWhenARequestAlreadyExistsForThisPolicyDecisionId() {
        when(guardPort.loadGuard(any(), any())).thenReturn(Optional.of(
            guardInStatus(TicketStatus.IN_PROGRESS, EXISTING_APPROVAL_REQUEST_ID)
        ));

        ApplyAutoApprovedPolicyResult result = service.applyAutoApprovedPolicy(command());

        assertThat(result.outcome()).isEqualTo(ApplyAutoApprovedPolicyOutcome.DUPLICATE);
        assertThat(result.approvalRequestId()).isEqualTo(EXISTING_APPROVAL_REQUEST_ID);
        verify(repository, never()).applyAutoApprovedPolicy(any());
        verify(historyWriter, never()).append(any());
        verify(outboxEventRepository, never()).append(any());
        verify(telemetry).recordApplyAutoApprovedPolicyOutcome("duplicate");
    }

    @Test
    void shouldReturnStaleWhenTheTicketDoesNotExist() {
        when(guardPort.loadGuard(any(), any())).thenReturn(Optional.empty());

        ApplyAutoApprovedPolicyResult result = service.applyAutoApprovedPolicy(command());

        assertThat(result.outcome()).isEqualTo(ApplyAutoApprovedPolicyOutcome.STALE);
        assertThat(result.approvalRequestId()).isNull();
        verify(repository, never()).applyAutoApprovedPolicy(any());
        verify(telemetry).recordApplyAutoApprovedPolicyOutcome("stale");
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {"IN_PROGRESS"}, mode = EnumSource.Mode.EXCLUDE)
    void shouldReturnStaleWhenTheTicketIsNotInProgress(TicketStatus otherStatus) {
        when(guardPort.loadGuard(any(), any())).thenReturn(Optional.of(guardInStatus(otherStatus, null)));

        ApplyAutoApprovedPolicyResult result = service.applyAutoApprovedPolicy(command());

        assertThat(result.outcome()).isEqualTo(ApplyAutoApprovedPolicyOutcome.STALE);
        verify(repository, never()).applyAutoApprovedPolicy(any());
    }

    @ParameterizedTest
    @EnumSource(value = ApprovalRiskLevel.class, names = {"LOW"}, mode = EnumSource.Mode.EXCLUDE)
    void shouldReturnRejectedBusinessRuleWhenRiskLevelIsNotEligibleForAutoApproval(ApprovalRiskLevel riskLevel) {
        ApplyAutoApprovedPolicyResult result = service.applyAutoApprovedPolicy(command(riskLevel));

        assertThat(result.outcome()).isEqualTo(ApplyAutoApprovedPolicyOutcome.REJECTED_BUSINESS_RULE);
        verify(repository, never()).applyAutoApprovedPolicy(any());
        verify(historyWriter, never()).append(any());
        verify(outboxEventRepository, never()).append(any());
        verify(telemetry).recordApplyAutoApprovedPolicyOutcome("rejected_business_rule");
    }

    @Test
    void shouldReturnStaleWhenTheTicketWriteRaceIsDetectedAtThePersistenceLayer() {
        org.mockito.Mockito.doReturn(new TicketAutoApprovedPolicyInsertOutcome.TicketConflict())
            .when(repository).applyAutoApprovedPolicy(any());

        ApplyAutoApprovedPolicyResult result = service.applyAutoApprovedPolicy(command());

        assertThat(result.outcome()).isEqualTo(ApplyAutoApprovedPolicyOutcome.STALE);
        verify(historyWriter, never()).append(any());
        verify(auditRecordPort, never()).append(any());
        verify(outboxEventRepository, never()).append(any());
    }

    @Test
    void shouldReturnDuplicateWhenTheApprovalIdWriteRaceIsDetectedAtThePersistenceLayer() {
        org.mockito.Mockito.doReturn(new TicketAutoApprovedPolicyInsertOutcome.DuplicateConflict())
            .when(repository).applyAutoApprovedPolicy(any());

        ApplyAutoApprovedPolicyResult result = service.applyAutoApprovedPolicy(command());

        assertThat(result.outcome()).isEqualTo(ApplyAutoApprovedPolicyOutcome.DUPLICATE);
        verify(historyWriter, never()).append(any());
        verify(auditRecordPort, never()).append(any());
        verify(outboxEventRepository, never()).append(any());
        verify(telemetry).recordApplyAutoApprovedPolicyOutcome("duplicate");
    }
}
