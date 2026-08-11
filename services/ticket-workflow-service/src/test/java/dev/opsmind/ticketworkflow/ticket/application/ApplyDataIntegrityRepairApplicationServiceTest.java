package dev.opsmind.ticketworkflow.ticket.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyDataIntegrityRepairCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyDataIntegrityRepairResult;
import dev.opsmind.ticketworkflow.ticket.application.event.IntegrityRepairAppliedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.DataIntegrityRepairConflictException;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.IntegrityRepairSourceNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.exception.RequestInProgressException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationOutcome;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.RequestHashCalculator;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.DataIntegrityRepairRecord;
import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.DataIntegrityRepairAttemptSummary;
import dev.opsmind.ticketworkflow.ticket.application.port.out.DataIntegrityRepairGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.DataIntegrityRepairGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.DataIntegrityRepairRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.IdempotencyRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.service.ApplyDataIntegrityRepairApplicationService;
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
 * SPEC-TW-041: the full success transaction, idempotency outcomes, the
 * caller-scope guard, the source-case-existence guard, and the open-attempt
 * conflict guard. Mirrors {@code ReplayEventApplicationServiceTest}'s
 * (SPEC-TW-038) shape.
 */
@Tag("unit")
class ApplyDataIntegrityRepairApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-09T12:00:00Z");
    private static final UUID TICKET_ID = UUID.fromString("6c2ad02e-c394-41fb-8e38-dfffd581a59d");
    private static final UUID SUPPORT_QUEUE_ID = UUID.fromString("9d38b723-4a4d-47d3-94fe-32ef78cc0690");
    private static final String SOURCE_REFERENCE = "3d1f6c6e-9a7b-4b8b-9f8e-1234567890ab";
    private static final String REASON = "Controlled recovery action for a verified inconsistency";

    private DataIntegrityRepairGuardPort guardPort;
    private DataIntegrityRepairRepository dataIntegrityRepairRepository;
    private AuditRecordPort auditRecordPort;
    private OutboxEventRepository outboxEventRepository;
    private IdempotencyRepository idempotencyRepository;
    private ClockPort clock;
    private TicketTelemetry telemetry;
    private ApplyDataIntegrityRepairApplicationService service;

    @BeforeEach
    void setUp() {
        guardPort = mock(DataIntegrityRepairGuardPort.class);
        dataIntegrityRepairRepository = mock(DataIntegrityRepairRepository.class);
        auditRecordPort = mock(AuditRecordPort.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        idempotencyRepository = mock(IdempotencyRepository.class);
        clock = mock(ClockPort.class);
        telemetry = mock(TicketTelemetry.class);

        when(clock.now()).thenReturn(NOW);
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.Reserved(UUID.randomUUID()));
        when(guardPort.loadTargetCase(any())).thenReturn(Optional.of(defaultGuard()));
        when(dataIntegrityRepairRepository.summarize(any(), any())).thenReturn(new DataIntegrityRepairAttemptSummary(0, false));

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new ApplyDataIntegrityRepairApplicationService(
            guardPort, dataIntegrityRepairRepository, auditRecordPort, outboxEventRepository, idempotencyRepository,
            clock, new RequestHashCalculator(objectMapper), new IntegrityRepairAppliedEventMapper(), telemetry, objectMapper
        );
    }

    private DataIntegrityRepairGuard defaultGuard() {
        return new DataIntegrityRepairGuard(TicketId.of(TICKET_ID), TicketDisplayId.of("INC-42"), SupportQueueId.of(SUPPORT_QUEUE_ID));
    }

    private ApplyDataIntegrityRepairCommand command(String idempotencyKey) {
        return new ApplyDataIntegrityRepairCommand(
            ReconciliationReasonCode.RECOVERY_REQUIRED, REASON, SOURCE_REFERENCE,
            new ActorContext("EMPLOYEE", "ops-operator", "ops-console", Set.of("ticket:reconciliation:repair")),
            idempotencyKey, "corr-041", "cmd-1", NOW
        );
    }

    @Test
    void shouldApplySuccessfullyAndPersistTheDecisionAuditOutboxAndIdempotency() {
        when(dataIntegrityRepairRepository.summarize(any(), any())).thenReturn(new DataIntegrityRepairAttemptSummary(2, false));

        ApplyDataIntegrityRepairResult result = service.apply(command("key-1"));

        assertThat(result.decision()).isEqualTo(ReconciliationDecision.APPLIED);
        assertThat(result.eventName()).isEqualTo("ticket.integrity-repair-applied.v1");
        assertThat(result.recoveryId()).isNotNull();
        assertThat(result.replayed()).isFalse();

        ArgumentCaptor<DataIntegrityRepairRecord> recordCaptor = ArgumentCaptor.forClass(DataIntegrityRepairRecord.class);
        verify(dataIntegrityRepairRepository).record(recordCaptor.capture());
        assertThat(recordCaptor.getValue().id()).isEqualTo(result.recoveryId());
        assertThat(recordCaptor.getValue().ticketId()).isEqualTo(TicketId.of(TICKET_ID));
        assertThat(recordCaptor.getValue().decision()).isEqualTo(ReconciliationDecision.APPLIED);
        assertThat(recordCaptor.getValue().reasonCode()).isEqualTo(ReconciliationReasonCode.RECOVERY_REQUIRED);
        assertThat(recordCaptor.getValue().sourceReference()).isEqualTo(SOURCE_REFERENCE);
        assertThat(recordCaptor.getValue().actorId()).isEqualTo("ops-operator");
        assertThat(recordCaptor.getValue().attemptNumber()).isEqualTo(3);
        assertThat(recordCaptor.getValue().completedAt()).isNull();

        ArgumentCaptor<AuditRecordEntry> auditCaptor = ArgumentCaptor.forClass(AuditRecordEntry.class);
        verify(auditRecordPort).append(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo("INTEGRITY_REPAIR_APPLIED");
        assertThat(auditCaptor.getValue().decision()).isEqualTo("ALLOWED");
        assertThat(auditCaptor.getValue().outcome()).isEqualTo("SUCCESS");

        ArgumentCaptor<OutboxEventEntry> outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntry.class);
        verify(outboxEventRepository).append(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().eventType()).isEqualTo("ticket.integrity-repair-applied");
        assertThat(outboxCaptor.getValue().routingKey()).isEqualTo("ticket.integrity-repair-applied.v1");
        assertThat(outboxCaptor.getValue().payload()).doesNotContainKey("reason");
        assertThat(outboxCaptor.getValue().payload().get("sourceReference")).isEqualTo(SOURCE_REFERENCE);
        assertThat(outboxCaptor.getValue().payload().get("decision")).isEqualTo("APPLIED");

        verify(idempotencyRepository).complete(any(), any());
        verify(telemetry).recordApplyDataIntegrityRepairCommand("success");
    }

    @Test
    void shouldReturnOriginalResponseOnReplayWithoutAnySideEffects() {
        UUID recoveryId = UUID.randomUUID();
        String storedJson = """
            {"recoveryId":"%s","decision":"APPLIED","eventName":"ticket.integrity-repair-applied.v1"}
            """.formatted(recoveryId);
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.Replayed(200, storedJson));

        ApplyDataIntegrityRepairResult result = service.apply(command("same-key"));

        assertThat(result.replayed()).isTrue();
        assertThat(result.recoveryId()).isEqualTo(recoveryId);
        assertThat(result.decision()).isEqualTo(ReconciliationDecision.APPLIED);
        verify(guardPort, never()).loadTargetCase(any());
        verify(dataIntegrityRepairRepository, never()).record(any());
        verify(auditRecordPort, never()).append(any());
        verify(outboxEventRepository, never()).append(any());
        verify(telemetry).recordApplyDataIntegrityRepairCommand("replay");
    }

    @Test
    void shouldRejectSameIdempotencyKeyWithADifferentPayload() {
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.KeyReused());

        assertThatThrownBy(() -> service.apply(command("same-key"))).isInstanceOf(IdempotencyKeyReusedException.class);
        verify(dataIntegrityRepairRepository, never()).record(any());
    }

    @Test
    void shouldRejectAFreshInProgressDuplicate() {
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.RequestInProgress());

        assertThatThrownBy(() -> service.apply(command("same-key"))).isInstanceOf(RequestInProgressException.class);
        verify(dataIntegrityRepairRepository, never()).record(any());
    }

    @Test
    void shouldRejectAnActorWithoutTheRequiredScope() {
        ApplyDataIntegrityRepairCommand command = new ApplyDataIntegrityRepairCommand(
            ReconciliationReasonCode.RECOVERY_REQUIRED, REASON, SOURCE_REFERENCE,
            new ActorContext("EMPLOYEE", "ops-operator", "ops-console", Set.of()),
            "key-1", "corr-041", "cmd-1", NOW
        );

        assertThatThrownBy(() -> service.apply(command)).isInstanceOf(TicketAuthorizationException.class);
        verify(idempotencyRepository, never()).reserve(any());
        verify(telemetry).recordApplyDataIntegrityRepairAuthorizationDenied();
    }

    @Test
    void shouldReturn404WhenTheSourceCaseDoesNotExist() {
        when(guardPort.loadTargetCase(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.apply(command("key-1"))).isInstanceOf(IntegrityRepairSourceNotFoundException.class);
        verify(dataIntegrityRepairRepository, never()).record(any());
    }

    @Test
    void shouldRejectWhenARepairIsAlreadyOpenForTheSameSourceReference() {
        when(dataIntegrityRepairRepository.summarize(any(), any())).thenReturn(new DataIntegrityRepairAttemptSummary(1, true));

        assertThatThrownBy(() -> service.apply(command("key-1"))).isInstanceOf(DataIntegrityRepairConflictException.class);
        verify(dataIntegrityRepairRepository, never()).record(any());
        verify(outboxEventRepository, never()).append(any());
        verify(idempotencyRepository, never()).complete(any(), any());
        verify(telemetry).recordApplyDataIntegrityRepairConflict();
    }
}
