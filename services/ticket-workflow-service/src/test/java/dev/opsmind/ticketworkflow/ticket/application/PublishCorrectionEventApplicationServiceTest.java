package dev.opsmind.ticketworkflow.ticket.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.PublishCorrectionEventCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.PublishCorrectionEventResult;
import dev.opsmind.ticketworkflow.ticket.application.event.CorrectionEventPublishedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.CorrectionEventConflictException;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.RequestInProgressException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationOutcome;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.RequestHashCalculator;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.CorrectionEventRecord;
import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.CorrectionEventAttemptSummary;
import dev.opsmind.ticketworkflow.ticket.application.port.out.CorrectionEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.IdempotencyRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketCorrectionEventGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketCorrectionEventGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.service.PublishCorrectionEventApplicationService;
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
 * SPEC-TW-039: the full success transaction, idempotency outcomes, the
 * caller-scope guard, the ticket-existence guard, and the open-attempt
 * conflict guard. Mirrors {@code OpenReconciliationCaseApplicationServiceTest}'s
 * (SPEC-TW-037) shape.
 */
@Tag("unit")
class PublishCorrectionEventApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-09T12:00:00Z");
    private static final UUID TICKET_ID = UUID.fromString("6c2ad02e-c394-41fb-8e38-dfffd581a59d");
    private static final UUID SUPPORT_QUEUE_ID = UUID.fromString("9d38b723-4a4d-47d3-94fe-32ef78cc0690");
    private static final String SOURCE_REFERENCE = "event-or-case-id";
    private static final String REASON = "Controlled recovery action for a verified inconsistency";

    private TicketCorrectionEventGuardPort guardPort;
    private CorrectionEventRepository correctionEventRepository;
    private AuditRecordPort auditRecordPort;
    private OutboxEventRepository outboxEventRepository;
    private IdempotencyRepository idempotencyRepository;
    private ClockPort clock;
    private TicketTelemetry telemetry;
    private PublishCorrectionEventApplicationService service;

    @BeforeEach
    void setUp() {
        guardPort = mock(TicketCorrectionEventGuardPort.class);
        correctionEventRepository = mock(CorrectionEventRepository.class);
        auditRecordPort = mock(AuditRecordPort.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        idempotencyRepository = mock(IdempotencyRepository.class);
        clock = mock(ClockPort.class);
        telemetry = mock(TicketTelemetry.class);

        when(clock.now()).thenReturn(NOW);
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.Reserved(UUID.randomUUID()));
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(defaultGuard()));
        when(correctionEventRepository.summarize(any(), any())).thenReturn(new CorrectionEventAttemptSummary(0, false));

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new PublishCorrectionEventApplicationService(
            guardPort, correctionEventRepository, auditRecordPort, outboxEventRepository, idempotencyRepository,
            clock, new RequestHashCalculator(objectMapper), new CorrectionEventPublishedEventMapper(), telemetry, objectMapper
        );
    }

    private TicketCorrectionEventGuard defaultGuard() {
        return new TicketCorrectionEventGuard(TicketId.of(TICKET_ID), TicketDisplayId.of("INC-42"), SupportQueueId.of(SUPPORT_QUEUE_ID));
    }

    private PublishCorrectionEventCommand command(String idempotencyKey) {
        return new PublishCorrectionEventCommand(
            TicketId.of(TICKET_ID), ReconciliationReasonCode.RECOVERY_REQUIRED, REASON, SOURCE_REFERENCE,
            new ActorContext("EMPLOYEE", "ops-operator", "ops-console", Set.of("ticket:reconciliation:correct")),
            idempotencyKey, "corr-039", "cmd-1", NOW
        );
    }

    @Test
    void shouldPublishSuccessfullyAndPersistTheDecisionAuditOutboxAndIdempotency() {
        when(correctionEventRepository.summarize(any(), any())).thenReturn(new CorrectionEventAttemptSummary(2, false));

        PublishCorrectionEventResult result = service.publish(command("key-1"));

        assertThat(result.decision()).isEqualTo(ReconciliationDecision.APPLIED);
        assertThat(result.eventName()).isEqualTo("ticket.correction-event-published.v1");
        assertThat(result.recoveryId()).isNotNull();
        assertThat(result.replayed()).isFalse();

        ArgumentCaptor<CorrectionEventRecord> recordCaptor = ArgumentCaptor.forClass(CorrectionEventRecord.class);
        verify(correctionEventRepository).record(recordCaptor.capture());
        assertThat(recordCaptor.getValue().id()).isEqualTo(result.recoveryId());
        assertThat(recordCaptor.getValue().decision()).isEqualTo(ReconciliationDecision.APPLIED);
        assertThat(recordCaptor.getValue().reasonCode()).isEqualTo(ReconciliationReasonCode.RECOVERY_REQUIRED);
        assertThat(recordCaptor.getValue().sourceReference()).isEqualTo(SOURCE_REFERENCE);
        assertThat(recordCaptor.getValue().actorId()).isEqualTo("ops-operator");
        assertThat(recordCaptor.getValue().attemptNumber()).isEqualTo(3);
        assertThat(recordCaptor.getValue().completedAt()).isNull();

        ArgumentCaptor<AuditRecordEntry> auditCaptor = ArgumentCaptor.forClass(AuditRecordEntry.class);
        verify(auditRecordPort).append(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo("CORRECTION_EVENT_PUBLISHED");
        assertThat(auditCaptor.getValue().decision()).isEqualTo("ALLOWED");
        assertThat(auditCaptor.getValue().outcome()).isEqualTo("SUCCESS");

        ArgumentCaptor<OutboxEventEntry> outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntry.class);
        verify(outboxEventRepository).append(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().eventType()).isEqualTo("ticket.correction-event-published");
        assertThat(outboxCaptor.getValue().routingKey()).isEqualTo("ticket.correction-event-published.v1");
        assertThat(outboxCaptor.getValue().payload()).doesNotContainKey("reason");
        assertThat(outboxCaptor.getValue().payload().get("sourceReference")).isEqualTo(SOURCE_REFERENCE);
        assertThat(outboxCaptor.getValue().payload().get("decision")).isEqualTo("APPLIED");

        verify(idempotencyRepository).complete(any(), any());
        verify(telemetry).recordPublishCorrectionEventCommand("success");
    }

    @Test
    void shouldReturnOriginalResponseOnReplayWithoutAnySideEffects() {
        UUID recoveryId = UUID.randomUUID();
        String storedJson = """
            {"recoveryId":"%s","decision":"APPLIED","eventName":"ticket.correction-event-published.v1"}
            """.formatted(recoveryId);
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.Replayed(200, storedJson));

        PublishCorrectionEventResult result = service.publish(command("same-key"));

        assertThat(result.replayed()).isTrue();
        assertThat(result.recoveryId()).isEqualTo(recoveryId);
        assertThat(result.decision()).isEqualTo(ReconciliationDecision.APPLIED);
        verify(guardPort, never()).loadGuard(any());
        verify(correctionEventRepository, never()).record(any());
        verify(auditRecordPort, never()).append(any());
        verify(outboxEventRepository, never()).append(any());
        verify(telemetry).recordPublishCorrectionEventCommand("replay");
    }

    @Test
    void shouldRejectSameIdempotencyKeyWithADifferentPayload() {
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.KeyReused());

        assertThatThrownBy(() -> service.publish(command("same-key"))).isInstanceOf(IdempotencyKeyReusedException.class);
        verify(correctionEventRepository, never()).record(any());
    }

    @Test
    void shouldRejectAFreshInProgressDuplicate() {
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.RequestInProgress());

        assertThatThrownBy(() -> service.publish(command("same-key"))).isInstanceOf(RequestInProgressException.class);
        verify(correctionEventRepository, never()).record(any());
    }

    @Test
    void shouldRejectAnActorWithoutTheRequiredScope() {
        PublishCorrectionEventCommand command = new PublishCorrectionEventCommand(
            TicketId.of(TICKET_ID), ReconciliationReasonCode.RECOVERY_REQUIRED, REASON, SOURCE_REFERENCE,
            new ActorContext("EMPLOYEE", "ops-operator", "ops-console", Set.of()),
            "key-1", "corr-039", "cmd-1", NOW
        );

        assertThatThrownBy(() -> service.publish(command)).isInstanceOf(TicketAuthorizationException.class);
        verify(idempotencyRepository, never()).reserve(any());
        verify(telemetry).recordPublishCorrectionEventAuthorizationDenied();
    }

    @Test
    void shouldReturn404WhenTheTicketDoesNotExist() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.publish(command("key-1"))).isInstanceOf(TicketNotFoundException.class);
        verify(correctionEventRepository, never()).record(any());
    }

    @Test
    void shouldRejectWhenACorrectionIsAlreadyOpenForTheSameSourceReference() {
        when(correctionEventRepository.summarize(any(), any())).thenReturn(new CorrectionEventAttemptSummary(1, true));

        assertThatThrownBy(() -> service.publish(command("key-1"))).isInstanceOf(CorrectionEventConflictException.class);
        verify(correctionEventRepository, never()).record(any());
        verify(outboxEventRepository, never()).append(any());
        verify(idempotencyRepository, never()).complete(any(), any());
        verify(telemetry).recordPublishCorrectionEventConflict();
    }
}
