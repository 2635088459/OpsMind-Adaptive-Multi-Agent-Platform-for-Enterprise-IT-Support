package dev.opsmind.ticketworkflow.ticket.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.RequestApprovalCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.RequestApprovalResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketApprovalRequiredBridgeEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketApprovalWaitStartedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.ApprovalRequestAlreadyOpenException;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.QueueAccessDeniedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.RequestInProgressException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketVersionConflictException;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationOutcome;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.RequestHashCalculator;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.TicketStatusHistoryEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.IdempotencyRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketApprovalRequestRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketApprovalRequestUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketApprovalRequestUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketStatusTransitionGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketStatusTransitionGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.service.RequestApprovalApplicationService;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidStatusTransitionException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.TicketNotAssignedException;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApprovalRiskLevel;
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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SPEC-TW-014: the full success transaction, guard rejections, and idempotency outcomes. */
@Tag("unit")
class RequestApprovalApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-03T18:00:00Z");
    private static final UUID TICKET_ID = UUID.fromString("6c2ad02e-c394-41fb-8e38-dfffd581a59d");
    private static final UUID SUPPORT_QUEUE_ID = UUID.fromString("9d38b723-4a4d-47d3-94fe-32ef78cc0690");
    private static final String TEAM_ID = "team-endpoint";
    private static final String ASSIGNEE_ID = "sam.support";
    private static final Map<String, Object> RISK_CONTEXT = Map.of("targetSystem", "identity");

    private TicketStatusTransitionGuardPort guardPort;
    private TicketApprovalRequestRepository requestRepository;
    private TicketHistoryWriter historyWriter;
    private AuditRecordPort auditRecordPort;
    private OutboxEventRepository outboxEventRepository;
    private IdempotencyRepository idempotencyRepository;
    private ClockPort clock;
    private TicketTelemetry telemetry;
    private RequestApprovalApplicationService service;

    @BeforeEach
    void setUp() {
        guardPort = mock(TicketStatusTransitionGuardPort.class);
        requestRepository = mock(TicketApprovalRequestRepository.class);
        historyWriter = mock(TicketHistoryWriter.class);
        auditRecordPort = mock(AuditRecordPort.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        idempotencyRepository = mock(IdempotencyRepository.class);
        clock = mock(ClockPort.class);
        telemetry = mock(TicketTelemetry.class);

        when(clock.now()).thenReturn(NOW);
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.Reserved(UUID.randomUUID()));
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(defaultGuard()));
        when(requestRepository.applyRequestApproval(any())).thenAnswer(invocation -> {
            TicketApprovalRequestUpdate update = invocation.getArgument(0);
            return new TicketApprovalRequestUpdateOutcome.Created(update.expectedVersion() + 1);
        });

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        RequestHashCalculator requestHashCalculator = new RequestHashCalculator(objectMapper);
        service = new RequestApprovalApplicationService(
            guardPort, requestRepository, historyWriter, auditRecordPort, outboxEventRepository, idempotencyRepository,
            clock, requestHashCalculator, new TicketApprovalWaitStartedEventMapper(),
            new TicketApprovalRequiredBridgeEventMapper(requestHashCalculator), telemetry, objectMapper
        );
    }

    private TicketStatusTransitionGuard defaultGuard() {
        return guardInStatus(TicketStatus.IN_PROGRESS, 20L);
    }

    private TicketStatusTransitionGuard guardInStatus(TicketStatus status, long version) {
        return new TicketStatusTransitionGuard(
            TicketId.of(TICKET_ID), TicketDisplayId.of("INC-42"), status, version,
            SupportQueueId.of(SUPPORT_QUEUE_ID), TEAM_ID, ASSIGNEE_ID
        );
    }

    private RequestApprovalCommand command(String idempotencyKey) {
        return new RequestApprovalCommand(
            TicketId.of(TICKET_ID), "wf-9000", "act-100", "RESET_MFA", ApprovalRiskLevel.HIGH, RISK_CONTEXT,
            "MFA reset requires approval before execution.", 20L,
            new ActorContext("IT_SUPPORT", "sam.support", "support-console", Set.of("ticket:request-approval")),
            Set.of(TEAM_ID), idempotencyKey, "corr-1", "cmd-1", NOW
        );
    }

    @Test
    void shouldRequestApprovalSuccessfullyAndPersistHistoryAuditOutboxAndIdempotency() {
        RequestApprovalResult result = service.requestApproval(command("key-1"));

        assertThat(result.status()).isEqualTo(TicketStatus.WAITING_FOR_APPROVAL);
        assertThat(result.previousStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(result.workflowId()).isEqualTo("wf-9000");
        assertThat(result.actionId()).isEqualTo("act-100");
        assertThat(result.actionType()).isEqualTo("RESET_MFA");
        assertThat(result.riskLevel()).isEqualTo(ApprovalRiskLevel.HIGH);
        assertThat(result.requestedBy()).isEqualTo("sam.support");
        assertThat(result.approvalId()).startsWith("appr-");
        assertThat(result.version()).isEqualTo(21L);
        assertThat(result.replayed()).isFalse();

        ArgumentCaptor<TicketStatusHistoryEntry> historyCaptor = ArgumentCaptor.forClass(TicketStatusHistoryEntry.class);
        verify(historyWriter).append(historyCaptor.capture());
        assertThat(historyCaptor.getValue().fromStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(historyCaptor.getValue().toStatus()).isEqualTo(TicketStatus.WAITING_FOR_APPROVAL);
        assertThat(historyCaptor.getValue().transitionId()).isEqualTo("SM-016");
        assertThat(historyCaptor.getValue().reasonCode()).isEqualTo("APPROVAL_REQUIRED");
        assertThat(historyCaptor.getValue().workflowId()).isEqualTo("wf-9000");
        assertThat(historyCaptor.getValue().aggregateVersion()).isEqualTo(21L);

        ArgumentCaptor<AuditRecordEntry> auditCaptor = ArgumentCaptor.forClass(AuditRecordEntry.class);
        verify(auditRecordPort).append(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo("APPROVAL_REQUESTED");
        assertThat(auditCaptor.getValue().outcome()).isEqualTo("SUCCESS");

        // Project-level integration verification (2026-09-01): this operation now
        // stages a SECOND outbox entry alongside its own -- a translation bridge
        // into policy-approval-governance-service's own consumer contract (see
        // TicketApprovalRequiredBridgeEventMapper's own javadoc) -- so append()
        // is called twice, not once.
        ArgumentCaptor<OutboxEventEntry> outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntry.class);
        verify(outboxEventRepository, times(2)).append(outboxCaptor.capture());
        OutboxEventEntry ownEvent = outboxCaptor.getAllValues().get(0);
        assertThat(ownEvent.eventType()).isEqualTo("ticket.approval-wait-started");
        assertThat(ownEvent.routingKey()).isEqualTo("ticket.approval-wait-started.v1");
        assertThat(ownEvent.payload()).doesNotContainKey("riskContext");

        OutboxEventEntry bridgeEvent = outboxCaptor.getAllValues().get(1);
        assertThat(bridgeEvent.eventType()).isEqualTo("ticket.approval.required.v1");
        assertThat(bridgeEvent.routingKey()).isEqualTo("ticket.approval.required.v1");
        assertThat(bridgeEvent.payload()).containsKeys("ticketId", "riskLevel", "inputHash");
        assertThat(bridgeEvent.payload()).doesNotContainKey("riskContext");

        verify(idempotencyRepository).complete(any(), any());
        verify(telemetry).recordRequestApprovalCommand("success");
    }

    @Test
    void shouldReturnOriginalResponseOnReplayWithoutAnySideEffects() {
        String storedJson = """
            {"ticketId":"%s","approvalRequestId":"3d912886-9652-4d88-8a64-1297b50f14c7","approvalId":"appr-1",\
            "previousStatus":"IN_PROGRESS","status":"WAITING_FOR_APPROVAL","workflowId":"wf-9000","actionId":"act-100",\
            "actionType":"RESET_MFA","riskLevel":"HIGH","requestedBy":"sam.support",\
            "requestedAt":"2026-08-03T18:00:00Z","version":21}
            """.formatted(TICKET_ID);
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.Replayed(201, storedJson));

        RequestApprovalResult result = service.requestApproval(command("same-key"));

        assertThat(result.replayed()).isTrue();
        assertThat(result.version()).isEqualTo(21L);
        verify(guardPort, never()).loadGuard(any());
        verify(requestRepository, never()).applyRequestApproval(any());
        verify(historyWriter, never()).append(any());
        verify(auditRecordPort, never()).append(any());
        verify(outboxEventRepository, never()).append(any());
        verify(telemetry).recordRequestApprovalCommand("replay");
    }

    @Test
    void shouldRejectSameIdempotencyKeyWithADifferentPayload() {
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.KeyReused());

        assertThatThrownBy(() -> service.requestApproval(command("same-key"))).isInstanceOf(IdempotencyKeyReusedException.class);
        verify(requestRepository, never()).applyRequestApproval(any());
    }

    @Test
    void shouldRejectAFreshInProgressDuplicate() {
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.RequestInProgress());

        assertThatThrownBy(() -> service.requestApproval(command("same-key"))).isInstanceOf(RequestInProgressException.class);
        verify(requestRepository, never()).applyRequestApproval(any());
    }

    @Test
    void shouldRejectAnActorWithoutTheRequiredScope() {
        RequestApprovalCommand command = new RequestApprovalCommand(
            TicketId.of(TICKET_ID), "wf-9000", "act-100", "RESET_MFA", ApprovalRiskLevel.HIGH, RISK_CONTEXT, null, 20L,
            new ActorContext("IT_SUPPORT", "sam.support", "support-console", Set.of()),
            Set.of(TEAM_ID), "key-1", "corr-1", "cmd-1", NOW
        );

        assertThatThrownBy(() -> service.requestApproval(command)).isInstanceOf(TicketAuthorizationException.class);
        verify(idempotencyRepository, never()).reserve(any());
    }

    @Test
    void shouldRejectATicketOutsideTheActorsAuthorizedQueue() {
        RequestApprovalCommand command = new RequestApprovalCommand(
            TicketId.of(TICKET_ID), "wf-9000", "act-100", "RESET_MFA", ApprovalRiskLevel.HIGH, RISK_CONTEXT, null, 20L,
            new ActorContext("IT_SUPPORT", "sam.support", "support-console", Set.of("ticket:request-approval")),
            Set.of("some-other-team"), "key-1", "corr-1", "cmd-1", NOW
        );

        assertThatThrownBy(() -> service.requestApproval(command)).isInstanceOf(QueueAccessDeniedException.class);
        verify(requestRepository, never()).applyRequestApproval(any());
    }

    @Test
    void shouldReturn404WhenTheTicketDoesNotExist() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requestApproval(command("key-1"))).isInstanceOf(TicketNotFoundException.class);
    }

    @Test
    void shouldRejectAStaleExpectedVersion() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(guardInStatus(TicketStatus.IN_PROGRESS, 21L)));

        assertThatThrownBy(() -> service.requestApproval(command("key-1")))
            .isInstanceOfSatisfying(TicketVersionConflictException.class, ex -> assertThat(ex.currentVersion()).isEqualTo(21L));
        verify(requestRepository, never()).applyRequestApproval(any());
    }

    @Test
    void shouldRejectATicketAlreadyWaitingForApprovalWithTheSpecificErrorCode() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(guardInStatus(TicketStatus.WAITING_FOR_APPROVAL, 20L)));

        assertThatThrownBy(() -> service.requestApproval(command("key-1"))).isInstanceOf(ApprovalRequestAlreadyOpenException.class);
        verify(requestRepository, never()).applyRequestApproval(any());
    }

    @Test
    void shouldRejectAnyOtherNonInProgressStatusWithInvalidStatusTransition() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(guardInStatus(TicketStatus.ASSIGNED, 20L)));

        assertThatThrownBy(() -> service.requestApproval(command("key-1")))
            .isInstanceOfSatisfying(InvalidStatusTransitionException.class, ex -> {
                assertThat(ex.currentStatus()).isEqualTo(TicketStatus.ASSIGNED);
                assertThat(ex.targetStatus()).isEqualTo(TicketStatus.WAITING_FOR_APPROVAL);
            });
    }

    @Test
    void shouldRejectAnUnassignedTicketDetectedAtTheRepositoryLayer() {
        org.mockito.Mockito.doReturn(new TicketApprovalRequestUpdateOutcome.NotAssigned()).when(requestRepository).applyRequestApproval(any());

        assertThatThrownBy(() -> service.requestApproval(command("key-1"))).isInstanceOf(TicketNotAssignedException.class);
    }

    @Test
    void shouldRejectARequestAlreadyOpenRaceDetectedAtTheRepositoryLayer() {
        org.mockito.Mockito.doReturn(new TicketApprovalRequestUpdateOutcome.RequestAlreadyOpen()).when(requestRepository).applyRequestApproval(any());

        assertThatThrownBy(() -> service.requestApproval(command("key-1"))).isInstanceOf(ApprovalRequestAlreadyOpenException.class);
    }
}
