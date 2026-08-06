package dev.opsmind.ticketworkflow.ticket.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.configuration.TicketWorkflowProperties;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.ResolveTicketWithVerificationCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ResolveTicketWithVerificationResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketResolvedWithVerificationEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.RequestInProgressException;
import dev.opsmind.ticketworkflow.ticket.application.exception.ResolutionCycleAlreadyCompletedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.ResolutionCycleNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketVersionConflictException;
import dev.opsmind.ticketworkflow.ticket.application.exception.VerificationEvidenceRequiredException;
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
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.application.port.out.VerifiedResolutionGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.VerifiedResolutionGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.VerifiedResolutionRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.VerifiedResolutionUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.VerifiedResolutionUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.application.port.out.VerifiedVerificationEvidence;
import dev.opsmind.ticketworkflow.ticket.application.service.ResolveTicketWithVerificationApplicationService;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidStatusTransitionException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.TicketNotAssignedException;
import dev.opsmind.ticketworkflow.ticket.domain.value.ResolutionCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.ResolutionCycleStatus;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SPEC-TW-025: the full success transaction, guard rejections, evidence lookup, and idempotency outcomes. Mirrors {@code StartVerificationApplicationServiceTest}'s (SPEC-TW-022) and {@code ResolveTicketApplicationServiceTest}'s (SPEC-TW-010) shapes. */
@Tag("unit")
class ResolveTicketWithVerificationApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-06T19:05:00Z");
    private static final UUID TICKET_ID = UUID.fromString("6c2ad02e-c394-41fb-8e38-dfffd581a59d");
    private static final UUID SUPPORT_QUEUE_ID = UUID.fromString("9d38b723-4a4d-47d3-94fe-32ef78cc0690");
    private static final UUID RESOLUTION_CYCLE_ID = UUID.fromString("4bde946d-60b8-4e4e-9970-6a0d0d1448f1");
    private static final String ASSIGNEE_ID = "sam.support";
    private static final String VERIFICATION_ID = "ver-1234";
    private static final String VERIFICATION_EVIDENCE_ID = "ve-300";
    private static final String SUMMARY = "Verification confirmed the requester can sign in after MFA reset.";

    private VerifiedResolutionGuardPort guardPort;
    private VerifiedResolutionRepository repository;
    private TicketHistoryWriter historyWriter;
    private AuditRecordPort auditRecordPort;
    private OutboxEventRepository outboxEventRepository;
    private IdempotencyRepository idempotencyRepository;
    private ClockPort clock;
    private TicketTelemetry telemetry;
    private ResolveTicketWithVerificationApplicationService service;

    @BeforeEach
    void setUp() {
        guardPort = mock(VerifiedResolutionGuardPort.class);
        repository = mock(VerifiedResolutionRepository.class);
        historyWriter = mock(TicketHistoryWriter.class);
        auditRecordPort = mock(AuditRecordPort.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        idempotencyRepository = mock(IdempotencyRepository.class);
        clock = mock(ClockPort.class);
        telemetry = mock(TicketTelemetry.class);

        when(clock.now()).thenReturn(NOW);
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.Reserved(UUID.randomUUID()));
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(defaultGuard()));
        when(repository.findCurrentSucceededEvidence(any(), any(), any()))
            .thenReturn(Optional.of(new VerifiedVerificationEvidence(VERIFICATION_ID, "wf-9000", 1)));
        when(repository.applyResolution(any())).thenAnswer(invocation -> {
            VerifiedResolutionUpdate update = invocation.getArgument(0);
            return new VerifiedResolutionUpdateOutcome.Updated(update.expectedVersion() + 1);
        });

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        TicketWorkflowProperties properties = new TicketWorkflowProperties(
            null, null,
            new TicketWorkflowProperties.Sla("DEFAULT", Duration.ofHours(4), Duration.ofHours(24), Duration.ofHours(4), Duration.ofDays(7))
        );
        service = new ResolveTicketWithVerificationApplicationService(
            guardPort, repository, historyWriter, auditRecordPort, outboxEventRepository, idempotencyRepository,
            clock, new RequestHashCalculator(objectMapper), new TicketResolvedWithVerificationEventMapper(), telemetry, objectMapper, properties
        );
    }

    private VerifiedResolutionGuard defaultGuard() {
        return guardInStatus(TicketStatus.VERIFYING, 17L, ASSIGNEE_ID);
    }

    private VerifiedResolutionGuard guardInStatus(TicketStatus status, long version, String assigneeId) {
        return new VerifiedResolutionGuard(
            TicketId.of(TICKET_ID), TicketDisplayId.of("INC-42"), status, version,
            SupportQueueId.of(SUPPORT_QUEUE_ID), assigneeId, RESOLUTION_CYCLE_ID, ResolutionCycleStatus.ACTIVE
        );
    }

    private ResolveTicketWithVerificationCommand command(String idempotencyKey) {
        return new ResolveTicketWithVerificationCommand(
            TicketId.of(TICKET_ID), VERIFICATION_EVIDENCE_ID, ResolutionCode.FIXED, SUMMARY, 17L,
            new ActorContext("SERVICE", "verification-orchestrator", "verification-service", Set.of("ticket:verified-resolution")),
            idempotencyKey, "corr-1", "cmd-1", NOW
        );
    }

    @Test
    void shouldResolveSuccessfullyAndPersistHistoryAuditOutboxAndIdempotency() {
        ResolveTicketWithVerificationResult result = service.resolveWithVerification(command("key-1"));

        assertThat(result.status()).isEqualTo(TicketStatus.RESOLVED);
        assertThat(result.previousStatus()).isEqualTo(TicketStatus.VERIFYING);
        assertThat(result.verificationId()).isEqualTo(VERIFICATION_ID);
        assertThat(result.verificationEvidenceId()).isEqualTo(VERIFICATION_EVIDENCE_ID);
        assertThat(result.resolutionCode()).isEqualTo(ResolutionCode.FIXED);
        assertThat(result.resolutionSummary()).isEqualTo(SUMMARY);
        assertThat(result.resolvedBy()).isEqualTo("verification-orchestrator");
        assertThat(result.resolutionCycleId()).isEqualTo(RESOLUTION_CYCLE_ID);
        assertThat(result.autoCloseDueAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        assertThat(result.version()).isEqualTo(18L);
        assertThat(result.replayed()).isFalse();

        ArgumentCaptor<TicketStatusHistoryEntry> historyCaptor = ArgumentCaptor.forClass(TicketStatusHistoryEntry.class);
        verify(historyWriter).append(historyCaptor.capture());
        assertThat(historyCaptor.getValue().fromStatus()).isEqualTo(TicketStatus.VERIFYING);
        assertThat(historyCaptor.getValue().toStatus()).isEqualTo(TicketStatus.RESOLVED);
        assertThat(historyCaptor.getValue().transitionId()).isEqualTo("SM-030");
        assertThat(historyCaptor.getValue().reasonCode()).isEqualTo("VERIFIED_RESOLUTION");
        assertThat(historyCaptor.getValue().aggregateVersion()).isEqualTo(18L);

        ArgumentCaptor<AuditRecordEntry> auditCaptor = ArgumentCaptor.forClass(AuditRecordEntry.class);
        verify(auditRecordPort).append(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo("TICKET_RESOLVED_WITH_VERIFICATION");
        assertThat(auditCaptor.getValue().outcome()).isEqualTo("SUCCESS");

        ArgumentCaptor<OutboxEventEntry> outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntry.class);
        verify(outboxEventRepository).append(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().eventType()).isEqualTo("ticket.resolved-with-verification");
        assertThat(outboxCaptor.getValue().routingKey()).isEqualTo("ticket.resolved-with-verification.v1");
        assertThat(outboxCaptor.getValue().payload()).containsEntry("verificationEvidenceId", VERIFICATION_EVIDENCE_ID);

        verify(idempotencyRepository).complete(any(), any());
        verify(telemetry).recordResolveWithVerificationCommand("success");
    }

    @Test
    void shouldReturnOriginalResponseOnReplayWithoutAnySideEffects() {
        String storedJson = """
            {"ticketId":"%s","previousStatus":"VERIFYING","status":"RESOLVED","verificationId":"ver-1234",\
            "verificationEvidenceId":"ve-300","resolutionCode":"FIXED","resolutionSummary":"%s",\
            "resolvedBy":"verification-orchestrator","resolvedAt":"2026-08-06T19:05:00Z",\
            "resolutionCycleId":"%s","autoCloseDueAt":"2026-08-13T19:05:00Z","version":18}
            """.formatted(TICKET_ID, SUMMARY, RESOLUTION_CYCLE_ID);
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.Replayed(200, storedJson));

        ResolveTicketWithVerificationResult result = service.resolveWithVerification(command("same-key"));

        assertThat(result.replayed()).isTrue();
        assertThat(result.version()).isEqualTo(18L);
        verify(guardPort, never()).loadGuard(any());
        verify(repository, never()).applyResolution(any());
        verify(historyWriter, never()).append(any());
        verify(auditRecordPort, never()).append(any());
        verify(outboxEventRepository, never()).append(any());
        verify(telemetry).recordResolveWithVerificationCommand("replay");
    }

    @Test
    void shouldRejectSameIdempotencyKeyWithADifferentPayload() {
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.KeyReused());

        assertThatThrownBy(() -> service.resolveWithVerification(command("same-key"))).isInstanceOf(IdempotencyKeyReusedException.class);
        verify(repository, never()).applyResolution(any());
    }

    @Test
    void shouldRejectAFreshInProgressDuplicate() {
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.RequestInProgress());

        assertThatThrownBy(() -> service.resolveWithVerification(command("same-key"))).isInstanceOf(RequestInProgressException.class);
        verify(repository, never()).applyResolution(any());
    }

    @Test
    void shouldRejectAnActorWithoutTheRequiredScope() {
        ResolveTicketWithVerificationCommand command = new ResolveTicketWithVerificationCommand(
            TicketId.of(TICKET_ID), VERIFICATION_EVIDENCE_ID, ResolutionCode.FIXED, SUMMARY, 17L,
            new ActorContext("SERVICE", "verification-orchestrator", "verification-service", Set.of()),
            "key-1", "corr-1", "cmd-1", NOW
        );

        assertThatThrownBy(() -> service.resolveWithVerification(command)).isInstanceOf(TicketAuthorizationException.class);
        verify(idempotencyRepository, never()).reserve(any());
    }

    @Test
    void shouldRejectANonServiceActorEvenWithTheRequiredScope() {
        ResolveTicketWithVerificationCommand command = new ResolveTicketWithVerificationCommand(
            TicketId.of(TICKET_ID), VERIFICATION_EVIDENCE_ID, ResolutionCode.FIXED, SUMMARY, 17L,
            new ActorContext("IT_SUPPORT", "sam.support", "support-console", Set.of("ticket:verified-resolution")),
            "key-1", "corr-1", "cmd-1", NOW
        );

        assertThatThrownBy(() -> service.resolveWithVerification(command)).isInstanceOf(TicketAuthorizationException.class);
        verify(idempotencyRepository, never()).reserve(any());
    }

    @Test
    void shouldReturn404WhenTheTicketDoesNotExist() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveWithVerification(command("key-1"))).isInstanceOf(TicketNotFoundException.class);
    }

    @Test
    void shouldRejectAStaleExpectedVersion() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(guardInStatus(TicketStatus.VERIFYING, 61L, ASSIGNEE_ID)));

        assertThatThrownBy(() -> service.resolveWithVerification(command("key-1")))
            .isInstanceOfSatisfying(TicketVersionConflictException.class, ex -> assertThat(ex.currentVersion()).isEqualTo(61L));
        verify(repository, never()).applyResolution(any());
    }

    @Test
    void shouldRejectANonVerifyingStatus() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(guardInStatus(TicketStatus.IN_PROGRESS, 17L, ASSIGNEE_ID)));

        assertThatThrownBy(() -> service.resolveWithVerification(command("key-1")))
            .isInstanceOfSatisfying(InvalidStatusTransitionException.class, ex -> {
                assertThat(ex.currentStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
                assertThat(ex.targetStatus()).isEqualTo(TicketStatus.RESOLVED);
            });
        verify(repository, never()).findCurrentSucceededEvidence(any(), any(), any());
    }

    @Test
    void shouldRejectAnUnassignedTicket() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(guardInStatus(TicketStatus.VERIFYING, 17L, null)));

        assertThatThrownBy(() -> service.resolveWithVerification(command("key-1"))).isInstanceOf(TicketNotAssignedException.class);
        verify(repository, never()).applyResolution(any());
    }

    @Test
    void shouldRejectATicketWithNoCurrentResolutionCycle() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(new VerifiedResolutionGuard(
            TicketId.of(TICKET_ID), TicketDisplayId.of("INC-42"), TicketStatus.VERIFYING, 17L,
            SupportQueueId.of(SUPPORT_QUEUE_ID), ASSIGNEE_ID, null, null
        )));

        assertThatThrownBy(() -> service.resolveWithVerification(command("key-1"))).isInstanceOf(ResolutionCycleNotFoundException.class);
    }

    @Test
    void shouldRejectAnAlreadyCompletedResolutionCycle() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(new VerifiedResolutionGuard(
            TicketId.of(TICKET_ID), TicketDisplayId.of("INC-42"), TicketStatus.VERIFYING, 17L,
            SupportQueueId.of(SUPPORT_QUEUE_ID), ASSIGNEE_ID, RESOLUTION_CYCLE_ID, ResolutionCycleStatus.RESOLVED
        )));

        assertThatThrownBy(() -> service.resolveWithVerification(command("key-1"))).isInstanceOf(ResolutionCycleAlreadyCompletedException.class);
    }

    @Test
    void shouldRejectWhenNoCurrentSucceededEvidenceIsFound() {
        when(repository.findCurrentSucceededEvidence(any(), any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveWithVerification(command("key-1"))).isInstanceOf(VerificationEvidenceRequiredException.class);
        verify(repository, never()).applyResolution(any());
    }

    @Test
    void shouldRejectAnUnassignedTicketDetectedAtTheRepositoryLayer() {
        doReturn(new VerifiedResolutionUpdateOutcome.NotAssigned()).when(repository).applyResolution(any());

        assertThatThrownBy(() -> service.resolveWithVerification(command("key-1"))).isInstanceOf(TicketNotAssignedException.class);
    }

    @Test
    void shouldRejectAVersionMismatchRaceDetectedAtTheRepositoryLayer() {
        doReturn(new VerifiedResolutionUpdateOutcome.VersionMismatch(99L)).when(repository).applyResolution(any());

        assertThatThrownBy(() -> service.resolveWithVerification(command("key-1")))
            .isInstanceOfSatisfying(TicketVersionConflictException.class, ex -> assertThat(ex.currentVersion()).isEqualTo(99L));
    }

    @Test
    void shouldRejectAResolutionCycleRaceDetectedAtTheRepositoryLayer() {
        doReturn(new VerifiedResolutionUpdateOutcome.ResolutionCycleConflict()).when(repository).applyResolution(any());

        assertThatThrownBy(() -> service.resolveWithVerification(command("key-1"))).isInstanceOf(ResolutionCycleAlreadyCompletedException.class);
    }

    @Test
    void shouldRejectATicketMissingRaceDetectedAtTheRepositoryLayer() {
        doReturn(new VerifiedResolutionUpdateOutcome.TicketMissing()).when(repository).applyResolution(any());

        assertThatThrownBy(() -> service.resolveWithVerification(command("key-1"))).isInstanceOf(TicketNotFoundException.class);
    }
}
