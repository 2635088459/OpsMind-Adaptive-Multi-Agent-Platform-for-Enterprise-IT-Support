package dev.opsmind.ticketworkflow.ticket.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyCompletion;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationOutcome;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationRequest;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.RequestHashCalculator;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.TicketStatusHistoryEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.in.ResolveTicketWithVerificationUseCase;
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
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketResolvedWithVerification;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidStatusTransitionException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.TicketNotAssignedException;
import dev.opsmind.ticketworkflow.ticket.domain.model.Ticket;
import dev.opsmind.ticketworkflow.ticket.domain.value.ResolutionCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.ResolutionCycleStatus;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * SPEC-TW-025 §1/domain-rules §1: {@code VERIFYING -> RESOLVED} with the
 * resolution cycle completed in the same write. Mirrors {@code
 * ResolveTicketApplicationService}'s (SPEC-TW-010) idempotency-then-guard
 * orchestration shape and its resolution-cycle guard, with two differences
 * that reflect this being a trusted-internal-service endpoint driven by
 * verification evidence rather than a human IT-support action: {@link
 * #authorize} requires the {@code SERVICE} actor type in addition to the
 * scope (no Support Queue / team check, mirroring {@code
 * StartVerificationApplicationService}, SPEC-TW-022), and a mandatory
 * evidence lookup — scoped to the ticket's *current* resolution cycle —
 * stands between the guard and the domain call; only a {@code SUCCEEDED}
 * evidence row found there is trusted enough to resolve.
 */
@Service
public class ResolveTicketWithVerificationApplicationService implements ResolveTicketWithVerificationUseCase {

    private static final String OPERATION_ID = "resolveTicketWithVerification";
    private static final String ROUTE_TEMPLATE = "/internal/v1/tickets/{ticketId}/verified-resolution";
    private static final String REQUIRED_SCOPE = "ticket:verified-resolution";
    private static final String REQUIRED_ACTOR_TYPE = "SERVICE";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(5);
    private static final int SUCCESS_HTTP_STATUS = 200;

    private final VerifiedResolutionGuardPort guardPort;
    private final VerifiedResolutionRepository repository;
    private final TicketHistoryWriter historyWriter;
    private final AuditRecordPort auditRecordPort;
    private final OutboxEventRepository outboxEventRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ClockPort clock;
    private final RequestHashCalculator requestHashCalculator;
    private final TicketResolvedWithVerificationEventMapper eventMapper;
    private final TicketTelemetry telemetry;
    private final ObjectMapper objectMapper;
    private final TicketWorkflowProperties properties;

    public ResolveTicketWithVerificationApplicationService(
        VerifiedResolutionGuardPort guardPort,
        VerifiedResolutionRepository repository,
        TicketHistoryWriter historyWriter,
        AuditRecordPort auditRecordPort,
        OutboxEventRepository outboxEventRepository,
        IdempotencyRepository idempotencyRepository,
        ClockPort clock,
        RequestHashCalculator requestHashCalculator,
        TicketResolvedWithVerificationEventMapper eventMapper,
        TicketTelemetry telemetry,
        ObjectMapper objectMapper,
        TicketWorkflowProperties properties
    ) {
        this.guardPort = guardPort;
        this.repository = repository;
        this.historyWriter = historyWriter;
        this.auditRecordPort = auditRecordPort;
        this.outboxEventRepository = outboxEventRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.clock = clock;
        this.requestHashCalculator = requestHashCalculator;
        this.eventMapper = eventMapper;
        this.telemetry = telemetry;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Transactional
    @Override
    public ResolveTicketWithVerificationResult resolveWithVerification(ResolveTicketWithVerificationCommand command) {
        var timer = telemetry.startResolveWithVerificationTimer();
        try {
            authorize(command.actor());
            Reservation reservation = reserveIdempotency(command);
            if (reservation.replayed() != null) {
                telemetry.recordResolveWithVerificationCommand("replay");
                return reservation.replayed();
            }
            Instant now = reservation.now();

            VerifiedResolutionGuard guard = guardPort.loadGuard(command.ticketId()).orElseThrow(TicketNotFoundException::new);
            checkVersion(guard, command.expectedVersion());
            checkStatus(guard);
            checkResolutionCycle(guard);

            VerifiedVerificationEvidence evidence = repository.findCurrentSucceededEvidence(
                    command.ticketId(), guard.currentResolutionCycleId(), command.verificationEvidenceId()
                )
                .orElseThrow(() -> {
                    telemetry.recordResolveWithVerificationConflict("evidence_required");
                    return new VerificationEvidenceRequiredException();
                });

            Instant autoCloseDueAt = now.plus(properties.sla().autoCloseDue());

            TicketResolvedWithVerification resolved = Ticket.resolveWithVerification(
                command.ticketId(), guard.status(), guard.currentAssigneeId(), guard.currentResolutionCycleId(),
                guard.version(), evidence.verificationId(), command.verificationEvidenceId(), command.resolutionCode(),
                command.resolutionSummary(), autoCloseDueAt, command.actor().actorType(), command.actor().subject(), now
            );

            applyUpdate(resolved);

            historyWriter.append(new TicketStatusHistoryEntry(
                UUID.randomUUID(), command.ticketId(), resolved.previousStatus(), resolved.newStatus(),
                resolved.transitionId(), resolved.reasonCode(), command.actor().actorType(), command.actor().subject(),
                command.commandId(), null, evidence.workflowId(), resolved.aggregateVersion(), now
            ));

            String traceId = currentTraceId();
            auditRecordPort.append(new AuditRecordEntry(
                UUID.randomUUID(), "BUSINESS_ACTION", "TICKET_RESOLVED_WITH_VERIFICATION", "ALLOWED",
                command.actor().actorType(), command.actor().subject(), command.actor().clientId(),
                "TICKET", command.ticketId().toString(), guard.displayId().value(),
                resolved.previousStatus().name(), resolved.newStatus().name(),
                traceId, command.commandId(), "SUCCESS", "INTERNAL", now, null, null
            ));
            outboxEventRepository.append(eventMapper.map(resolved, guard.supportQueueId(), traceId, command.correlationId(), command.commandId()));

            ResolveTicketWithVerificationResult result = new ResolveTicketWithVerificationResult(
                command.ticketId(), resolved.previousStatus(), resolved.newStatus(), resolved.verificationId(),
                resolved.verificationEvidenceId(), resolved.resolutionCode(), resolved.resolutionSummary(),
                resolved.resolvedById(), resolved.resolvedAt(), resolved.resolutionCycleId(), resolved.autoCloseDueAt(),
                resolved.aggregateVersion(), false
            );
            completeIdempotency(reservation.idempotencyRecordId(), command.ticketId(), result, now);
            telemetry.recordResolveWithVerificationCommand("success");
            return result;
        } finally {
            telemetry.stopResolveWithVerificationTimer(timer);
        }
    }

    private void authorize(ActorContext actor) {
        if (!REQUIRED_ACTOR_TYPE.equals(actor.actorType()) || !actor.hasScope(REQUIRED_SCOPE)) {
            telemetry.recordResolveWithVerificationAuthorizationDenied();
            throw new TicketAuthorizationException(REQUIRED_SCOPE);
        }
    }

    private void checkVersion(VerifiedResolutionGuard guard, long expectedVersion) {
        if (guard.version() != expectedVersion) {
            telemetry.recordResolveWithVerificationConflict("version");
            throw new TicketVersionConflictException(guard.version());
        }
    }

    private void checkStatus(VerifiedResolutionGuard guard) {
        if (guard.status() != TicketStatus.VERIFYING) {
            telemetry.recordResolveWithVerificationConflict("state");
            throw new InvalidStatusTransitionException(guard.status(), TicketStatus.RESOLVED);
        }
        if (guard.currentAssigneeId() == null) {
            telemetry.recordResolveWithVerificationConflict("not_assigned");
            throw new TicketNotAssignedException();
        }
    }

    private void checkResolutionCycle(VerifiedResolutionGuard guard) {
        if (guard.currentResolutionCycleId() == null || guard.resolutionCycleStatus() == null) {
            telemetry.recordResolveWithVerificationConflict("cycle_not_found");
            throw new ResolutionCycleNotFoundException();
        }
        if (guard.resolutionCycleStatus() != ResolutionCycleStatus.ACTIVE) {
            telemetry.recordResolveWithVerificationConflict("cycle_already_completed");
            throw new ResolutionCycleAlreadyCompletedException();
        }
    }

    private void applyUpdate(TicketResolvedWithVerification resolved) {
        VerifiedResolutionUpdateOutcome outcome = repository.applyResolution(new VerifiedResolutionUpdate(
            resolved.ticketId(), resolved.aggregateVersion() - 1, resolved.resolutionCycleId(), resolved.verificationId(),
            resolved.verificationEvidenceId(), resolved.resolutionCode(), resolved.resolutionSummary(),
            resolved.resolvedByType(), resolved.resolvedById(), resolved.resolvedAt(), resolved.autoCloseDueAt(), resolved.occurredAt()
        ));
        if (outcome instanceof VerifiedResolutionUpdateOutcome.TicketMissing) {
            throw new TicketNotFoundException();
        }
        if (outcome instanceof VerifiedResolutionUpdateOutcome.VersionMismatch mismatch) {
            telemetry.recordResolveWithVerificationConflict("version");
            throw new TicketVersionConflictException(mismatch.currentVersion());
        }
        if (outcome instanceof VerifiedResolutionUpdateOutcome.NotAssigned) {
            telemetry.recordResolveWithVerificationConflict("not_assigned");
            throw new TicketNotAssignedException();
        }
        if (outcome instanceof VerifiedResolutionUpdateOutcome.InvalidState invalidState) {
            telemetry.recordResolveWithVerificationConflict("state");
            throw new InvalidStatusTransitionException(invalidState.currentStatus(), resolved.newStatus());
        }
        if (outcome instanceof VerifiedResolutionUpdateOutcome.ResolutionCycleConflict) {
            telemetry.recordResolveWithVerificationConflict("cycle_already_completed");
            throw new ResolutionCycleAlreadyCompletedException();
        }
    }

    private record Reservation(UUID idempotencyRecordId, Instant now, ResolveTicketWithVerificationResult replayed) {
    }

    private Reservation reserveIdempotency(ResolveTicketWithVerificationCommand command) {
        String actorScope = command.actor().actorType().toLowerCase() + ":" + command.actor().subject() + ":" + command.ticketId() + ":" + OPERATION_ID;
        String requestHash = requestHashCalculator.calculate("POST", ROUTE_TEMPLATE, actorScope, canonicalBody(command));

        Instant now = clock.now();
        UUID idempotencyRecordId = UUID.randomUUID();
        IdempotencyReservationOutcome outcome = idempotencyRepository.reserve(new IdempotencyReservationRequest(
            idempotencyRecordId, actorScope, command.idempotencyKey(), OPERATION_ID, requestHash, now, IDEMPOTENCY_TTL, STALE_THRESHOLD
        ));
        if (outcome instanceof IdempotencyReservationOutcome.Replayed replayed) {
            return new Reservation(idempotencyRecordId, now, deserializeResult(replayed.responseBodyJson()));
        }
        if (outcome instanceof IdempotencyReservationOutcome.KeyReused) {
            throw new IdempotencyKeyReusedException(command.idempotencyKey());
        }
        if (outcome instanceof IdempotencyReservationOutcome.RequestInProgress) {
            throw new RequestInProgressException(command.idempotencyKey());
        }
        IdempotencyReservationOutcome.Reserved reserved = (IdempotencyReservationOutcome.Reserved) outcome;
        return new Reservation(reserved.idempotencyRecordId(), now, null);
    }

    private Map<String, Object> canonicalBody(ResolveTicketWithVerificationCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticketId", command.ticketId().toString());
        body.put("verificationEvidenceId", command.verificationEvidenceId());
        body.put("resolutionCode", command.resolutionCode().name());
        body.put("resolutionSummary", command.resolutionSummary());
        body.put("expectedVersion", command.expectedVersion());
        return body;
    }

    private void completeIdempotency(UUID idempotencyRecordId, TicketId ticketId, ResolveTicketWithVerificationResult result, Instant now) {
        idempotencyRepository.complete(idempotencyRecordId, new IdempotencyCompletion(
            "TICKET", ticketId.toString(), SUCCESS_HTTP_STATUS, serializeResult(result), now
        ));
    }

    private String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }

    private String serializeResult(ResolveTicketWithVerificationResult result) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ticketId", result.ticketId().value().toString());
            body.put("previousStatus", result.previousStatus().name());
            body.put("status", result.status().name());
            body.put("verificationId", result.verificationId());
            body.put("verificationEvidenceId", result.verificationEvidenceId());
            body.put("resolutionCode", result.resolutionCode().name());
            body.put("resolutionSummary", result.resolutionSummary());
            body.put("resolvedBy", result.resolvedBy());
            body.put("resolvedAt", result.resolvedAt().toString());
            body.put("resolutionCycleId", result.resolutionCycleId().toString());
            body.put("autoCloseDueAt", result.autoCloseDueAt() == null ? null : result.autoCloseDueAt().toString());
            body.put("version", result.version());
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize resolve ticket with verification result", e);
        }
    }

    private ResolveTicketWithVerificationResult deserializeResult(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.readValue(json, Map.class);
            String autoCloseDueAtRaw = (String) body.get("autoCloseDueAt");
            return new ResolveTicketWithVerificationResult(
                TicketId.of(UUID.fromString((String) body.get("ticketId"))),
                TicketStatus.valueOf((String) body.get("previousStatus")),
                TicketStatus.valueOf((String) body.get("status")),
                (String) body.get("verificationId"),
                (String) body.get("verificationEvidenceId"),
                ResolutionCode.valueOf((String) body.get("resolutionCode")),
                (String) body.get("resolutionSummary"),
                (String) body.get("resolvedBy"),
                Instant.parse((String) body.get("resolvedAt")),
                UUID.fromString((String) body.get("resolutionCycleId")),
                autoCloseDueAtRaw == null ? null : Instant.parse(autoCloseDueAtRaw),
                ((Number) body.get("version")).longValue(),
                true
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored idempotency response", e);
        }
    }
}
