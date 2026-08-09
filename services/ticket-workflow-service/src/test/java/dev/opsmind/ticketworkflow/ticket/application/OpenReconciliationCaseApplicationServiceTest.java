package dev.opsmind.ticketworkflow.ticket.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.OpenReconciliationCaseCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.OpenReconciliationCaseResult;
import dev.opsmind.ticketworkflow.ticket.application.event.ReconciliationCaseOpenedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.ReconciliationCaseConflictException;
import dev.opsmind.ticketworkflow.ticket.application.exception.RequestInProgressException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationOutcome;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.RequestHashCalculator;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.ReconciliationCaseRecord;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.IdempotencyRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ReconciliationCaseAttemptSummary;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ReconciliationCaseRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketReconciliationGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketReconciliationGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.service.OpenReconciliationCaseApplicationService;
import dev.opsmind.ticketworkflow.ticket.domain.value.ReconciliationDecision;
import dev.opsmind.ticketworkflow.ticket.domain.value.ReconciliationReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SPEC-TW-037: the full success transaction, idempotency outcomes, the
 * caller-scope guard, the ticket-existence guard, and the open-case conflict
 * guard. Mirrors {@code AutoCloseTicketApplicationServiceTest}'s
 * (SPEC-TW-027) shape.
 */
@Tag("unit")
class OpenReconciliationCaseApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-09T12:00:00Z");
    private static final UUID TICKET_ID = UUID.fromString("6c2ad02e-c394-41fb-8e38-dfffd581a59d");
    private static final UUID SUPPORT_QUEUE_ID = UUID.fromString("9d38b723-4a4d-47d3-94fe-32ef78cc0690");
    private static final String SOURCE_REFERENCE = "event-or-case-id";
    private static final String REASON = "Controlled recovery action for a verified inconsistency";

    private TicketReconciliationGuardPort guardPort;
    private ReconciliationCaseRepository caseRepository;
    private AuditRecordPort auditRecordPort;
    private OutboxEventRepository outboxEventRepository;
    private IdempotencyRepository idempotencyRepository;
    private ClockPort clock;
    private TicketTelemetry telemetry;
    private OpenReconciliationCaseApplicationService service;

    @BeforeEach
    void setUp() {
        guardPort = mock(TicketReconciliationGuardPort.class);
        caseRepository = mock(ReconciliationCaseRepository.class);
        auditRecordPort = mock(AuditRecordPort.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        idempotencyRepository = mock(IdempotencyRepository.class);
        clock = mock(ClockPort.class);
        telemetry = mock(TicketTelemetry.class);

        when(clock.now()).thenReturn(NOW);
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.Reserved(UUID.randomUUID()));
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(defaultGuard()));
        when(caseRepository.summarize(any(), any())).thenReturn(new ReconciliationCaseAttemptSummary(0, false));

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new OpenReconciliationCaseApplicationService(
            guardPort, caseRepository, auditRecordPort, outboxEventRepository, idempotencyRepository,
            clock, new RequestHashCalculator(objectMapper), new ReconciliationCaseOpenedEventMapper(), telemetry, objectMapper
        );
    }

    private TicketReconciliationGuard defaultGuard() {
        return new TicketReconciliationGuard(TicketId.of(TICKET_ID), TicketDisplayId.of("INC-42"), SupportQueueId.of(SUPPORT_QUEUE_ID));
    }

    private OpenReconciliationCaseCommand command(String idempotencyKey) {
        return new OpenReconciliationCaseCommand(
            TicketId.of(TICKET_ID), ReconciliationReasonCode.RECOVERY_REQUIRED, REASON, SOURCE_REFERENCE,
            new ActorContext("EMPLOYEE", "ops-operator", "ops-console", Set.of("ticket:reconciliation:open")),
            idempotencyKey, "corr-037", "cmd-1", NOW
        );
    }

    @Test
    void shouldOpenACaseSuccessfullyAndPersistTheDecisionAuditOutboxAndIdempotency() {
        when(caseRepository.summarize(any(), any())).thenReturn(new ReconciliationCaseAttemptSummary(2, false));

        OpenReconciliationCaseResult result = service.openCase(command("key-1"));

        assertThat(result.decision()).isEqualTo(ReconciliationDecision.APPLIED);
        assertThat(result.eventName()).isEqualTo("ticket.reconciliation-case-opened.v1");
        assertThat(result.recoveryId()).isNotNull();
        assertThat(result.replayed()).isFalse();

        ArgumentCaptor<ReconciliationCaseRecord> caseCaptor = ArgumentCaptor.forClass(ReconciliationCaseRecord.class);
        verify(caseRepository).record(caseCaptor.capture());
        assertThat(caseCaptor.getValue().id()).isEqualTo(result.recoveryId());
        assertThat(caseCaptor.getValue().decision()).isEqualTo(ReconciliationDecision.APPLIED);
        assertThat(caseCaptor.getValue().reasonCode()).isEqualTo(ReconciliationReasonCode.RECOVERY_REQUIRED);
        assertThat(caseCaptor.getValue().sourceReference()).isEqualTo(SOURCE_REFERENCE);
        assertThat(caseCaptor.getValue().actorId()).isEqualTo("ops-operator");
        assertThat(caseCaptor.getValue().attemptNumber()).isEqualTo(3);
        assertThat(caseCaptor.getValue().completedAt()).isNull();

        ArgumentCaptor<AuditRecordEntry> auditCaptor = ArgumentCaptor.forClass(AuditRecordEntry.class);
        verify(auditRecordPort).append(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo("RECONCILIATION_CASE_OPENED");
        assertThat(auditCaptor.getValue().decision()).isEqualTo("ALLOWED");
        assertThat(auditCaptor.getValue().outcome()).isEqualTo("SUCCESS");

        ArgumentCaptor<OutboxEventEntry> outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntry.class);
        verify(outboxEventRepository).append(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().eventType()).isEqualTo("ticket.reconciliation-case-opened");
        assertThat(outboxCaptor.getValue().routingKey()).isEqualTo("ticket.reconciliation-case-opened.v1");
        assertThat(outboxCaptor.getValue().payload()).doesNotContainKey("reason");
        assertThat(outboxCaptor.getValue().payload().get("sourceReference")).isEqualTo(SOURCE_REFERENCE);
        assertThat(outboxCaptor.getValue().payload().get("decision")).isEqualTo("APPLIED");

        verify(idempotencyRepository).complete(any(), any());
        verify(telemetry).recordOpenReconciliationCaseCommand("success");
    }

    @Test
    void shouldReturnOriginalResponseOnReplayWithoutAnySideEffects() {
        UUID recoveryId = UUID.randomUUID();
        String storedJson = """
            {"recoveryId":"%s","decision":"APPLIED","eventName":"ticket.reconciliation-case-opened.v1"}
            """.formatted(recoveryId);
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.Replayed(200, storedJson));

        OpenReconciliationCaseResult result = service.openCase(command("same-key"));

        assertThat(result.replayed()).isTrue();
        assertThat(result.recoveryId()).isEqualTo(recoveryId);
        assertThat(result.decision()).isEqualTo(ReconciliationDecision.APPLIED);
        verify(guardPort, never()).loadGuard(any());
        verify(caseRepository, never()).record(any());
        verify(auditRecordPort, never()).append(any());
        verify(outboxEventRepository, never()).append(any());
        verify(telemetry).recordOpenReconciliationCaseCommand("replay");
    }

    @Test
    void shouldRejectSameIdempotencyKeyWithADifferentPayload() {
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.KeyReused());

        assertThatThrownBy(() -> service.openCase(command("same-key"))).isInstanceOf(IdempotencyKeyReusedException.class);
        verify(caseRepository, never()).record(any());
    }

    @Test
    void shouldRejectAFreshInProgressDuplicate() {
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.RequestInProgress());

        assertThatThrownBy(() -> service.openCase(command("same-key"))).isInstanceOf(RequestInProgressException.class);
        verify(caseRepository, never()).record(any());
    }

    @Test
    void shouldRejectAnActorWithoutTheRequiredScope() {
        OpenReconciliationCaseCommand command = new OpenReconciliationCaseCommand(
            TicketId.of(TICKET_ID), ReconciliationReasonCode.RECOVERY_REQUIRED, REASON, SOURCE_REFERENCE,
            new ActorContext("EMPLOYEE", "ops-operator", "ops-console", Set.of()),
            "key-1", "corr-037", "cmd-1", NOW
        );

        assertThatThrownBy(() -> service.openCase(command)).isInstanceOf(TicketAuthorizationException.class);
        verify(idempotencyRepository, never()).reserve(any());
        verify(telemetry).recordOpenReconciliationCaseAuthorizationDenied();
    }

    @Test
    void shouldReturn404WhenTheTicketDoesNotExist() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.openCase(command("key-1"))).isInstanceOf(TicketNotFoundException.class);
        verify(caseRepository, never()).record(any());
    }

    @Test
    void shouldRejectWhenACaseIsAlreadyOpenForTheSameSourceReference() {
        when(caseRepository.summarize(any(), any())).thenReturn(new ReconciliationCaseAttemptSummary(1, true));

        assertThatThrownBy(() -> service.openCase(command("key-1"))).isInstanceOf(ReconciliationCaseConflictException.class);
        verify(caseRepository, never()).record(any());
        verify(outboxEventRepository, never()).append(any());
        verify(idempotencyRepository, never()).complete(any(), any());
        verify(telemetry).recordOpenReconciliationCaseConflict();
    }
}
