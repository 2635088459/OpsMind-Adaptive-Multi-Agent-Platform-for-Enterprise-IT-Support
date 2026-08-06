package dev.opsmind.ticketworkflow.ticket.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.StartVerificationCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.StartVerificationResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketVerificationStartedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.RequestInProgressException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketVersionConflictException;
import dev.opsmind.ticketworkflow.ticket.application.exception.VerificationAttemptAlreadyActiveException;
import dev.opsmind.ticketworkflow.ticket.application.exception.VerificationToolResultInvalidException;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyCompletion;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationOutcome;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationRequest;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.RequestHashCalculator;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.TicketStatusHistoryEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.in.StartVerificationUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.CompletedToolResultReference;
import dev.opsmind.ticketworkflow.ticket.application.port.out.IdempotencyRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketVerificationStartGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketVerificationStartGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketVerificationStartRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketVerificationStartUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketVerificationStartUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketVerificationStarted;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidStatusTransitionException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.TicketNotAssignedException;
import dev.opsmind.ticketworkflow.ticket.domain.model.Ticket;
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
 * SPEC-TW-022 §1/domain-rules §1: {@code VERIFYING -> VERIFYING} with a new
 * {@code ticket_verification_attempts} row. Mirrors {@code
 * RequestApprovalApplicationService}'s (SPEC-TW-014) idempotency-then-guard
 * orchestration shape, with two differences that reflect this being a
 * trusted-internal-service endpoint rather than a human IT-support action:
 * {@link #authorize} requires the {@code SERVICE} actor type in addition to
 * the scope (no Support Queue / team check — domain-rules §"clients cannot
 * spoof verification result" is a service-identity trust boundary, not a
 * queue-membership one), and the guard additionally validates {@code
 * toolResultId} against Phase 06's {@code ticket_tool_execution_results}
 * and the "one active attempt per tool result" invariant before ever
 * touching the ticket row.
 */
@Service
public class StartVerificationApplicationService implements StartVerificationUseCase {

    private static final String OPERATION_ID = "startVerification";
    private static final String ROUTE_TEMPLATE = "/internal/v1/tickets/{ticketId}/verification/start";
    private static final String REQUIRED_SCOPE = "ticket:verification-start";
    private static final String REQUIRED_ACTOR_TYPE = "SERVICE";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(5);
    private static final int SUCCESS_HTTP_STATUS = 201;

    private final TicketVerificationStartGuardPort guardPort;
    private final TicketVerificationStartRepository repository;
    private final TicketHistoryWriter historyWriter;
    private final AuditRecordPort auditRecordPort;
    private final OutboxEventRepository outboxEventRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ClockPort clock;
    private final RequestHashCalculator requestHashCalculator;
    private final TicketVerificationStartedEventMapper eventMapper;
    private final TicketTelemetry telemetry;
    private final ObjectMapper objectMapper;

    public StartVerificationApplicationService(
        TicketVerificationStartGuardPort guardPort,
        TicketVerificationStartRepository repository,
        TicketHistoryWriter historyWriter,
        AuditRecordPort auditRecordPort,
        OutboxEventRepository outboxEventRepository,
        IdempotencyRepository idempotencyRepository,
        ClockPort clock,
        RequestHashCalculator requestHashCalculator,
        TicketVerificationStartedEventMapper eventMapper,
        TicketTelemetry telemetry,
        ObjectMapper objectMapper
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
    }

    @Transactional
    @Override
    public StartVerificationResult startVerification(StartVerificationCommand command) {
        var timer = telemetry.startStartVerificationTimer();
        try {
            authorize(command.actor());
            Reservation reservation = reserveIdempotency(command);
            if (reservation.replayed() != null) {
                telemetry.recordStartVerificationCommand("replay");
                return reservation.replayed();
            }
            Instant now = reservation.now();

            TicketVerificationStartGuard guard = guardPort.loadGuard(command.ticketId()).orElseThrow(TicketNotFoundException::new);
            checkVersion(guard, command.expectedVersion());
            checkStatus(guard);

            CompletedToolResultReference toolResult = repository.findCompletedToolResult(command.ticketId(), command.toolResultId())
                .orElseThrow(() -> {
                    telemetry.recordStartVerificationConflict("tool_result_invalid");
                    return new VerificationToolResultInvalidException();
                });

            if (repository.hasActiveAttempt(command.toolResultId())) {
                telemetry.recordStartVerificationConflict("attempt_already_active");
                throw new VerificationAttemptAlreadyActiveException();
            }

            int attemptNumber = repository.nextAttemptNumber(command.toolResultId());
            String verificationId = "ver-" + UUID.randomUUID();

            TicketVerificationStarted started = Ticket.startVerification(
                command.ticketId(), guard.status(), guard.currentAssigneeId(), guard.version(), verificationId,
                guard.currentResolutionCycleId(), toolResult.workflowId(), command.toolResultId(), attemptNumber,
                command.verificationType(), command.reason(), command.actor().actorType(), command.actor().subject(), now
            );

            applyUpdate(started, command);

            historyWriter.append(new TicketStatusHistoryEntry(
                UUID.randomUUID(), command.ticketId(), started.previousStatus(), started.newStatus(),
                started.transitionId(), started.reasonCode(), command.actor().actorType(), command.actor().subject(),
                command.commandId(), null, started.workflowId(), started.aggregateVersion(), now
            ));

            String traceId = currentTraceId();
            auditRecordPort.append(new AuditRecordEntry(
                UUID.randomUUID(), "BUSINESS_ACTION", "VERIFICATION_STARTED", "ALLOWED",
                command.actor().actorType(), command.actor().subject(), command.actor().clientId(),
                "TICKET", command.ticketId().toString(), guard.displayId().value(),
                started.previousStatus().name(), started.newStatus().name(),
                traceId, command.commandId(), "SUCCESS", "INTERNAL", now, null, null
            ));
            outboxEventRepository.append(eventMapper.map(started, guard.supportQueueId(), traceId, command.correlationId(), command.commandId()));

            StartVerificationResult result = new StartVerificationResult(
                command.ticketId(), started.verificationId(), started.resolutionCycleId(), started.workflowId(), started.toolResultId(),
                started.attemptNumber(), started.verificationType(), started.previousStatus(), started.newStatus(),
                started.startedById(), started.startedAt(), started.aggregateVersion(), false
            );
            completeIdempotency(reservation.idempotencyRecordId(), command.ticketId(), result, now);
            telemetry.recordStartVerificationCommand("success");
            return result;
        } finally {
            telemetry.stopStartVerificationTimer(timer);
        }
    }

    private void authorize(ActorContext actor) {
        if (!REQUIRED_ACTOR_TYPE.equals(actor.actorType()) || !actor.hasScope(REQUIRED_SCOPE)) {
            telemetry.recordStartVerificationAuthorizationDenied();
            throw new TicketAuthorizationException(REQUIRED_SCOPE);
        }
    }

    private void checkVersion(TicketVerificationStartGuard guard, long expectedVersion) {
        if (guard.version() != expectedVersion) {
            telemetry.recordStartVerificationConflict("version");
            throw new TicketVersionConflictException(guard.version());
        }
    }

    private void checkStatus(TicketVerificationStartGuard guard) {
        if (guard.status() != TicketStatus.VERIFYING) {
            telemetry.recordStartVerificationConflict("state");
            throw new InvalidStatusTransitionException(guard.status(), TicketStatus.VERIFYING);
        }
        if (guard.currentAssigneeId() == null) {
            telemetry.recordStartVerificationConflict("not_assigned");
            throw new TicketNotAssignedException();
        }
    }

    private void applyUpdate(TicketVerificationStarted started, StartVerificationCommand command) {
        TicketVerificationStartUpdateOutcome outcome = repository.startVerification(new TicketVerificationStartUpdate(
            started.ticketId(), started.aggregateVersion() - 1, started.verificationId(), started.resolutionCycleId(),
            started.workflowId(), started.toolResultId(), started.attemptNumber(), started.verificationType(),
            started.startedAt(), started.occurredAt()
        ));
        if (outcome instanceof TicketVerificationStartUpdateOutcome.TicketMissing) {
            throw new TicketNotFoundException();
        }
        if (outcome instanceof TicketVerificationStartUpdateOutcome.VersionMismatch mismatch) {
            telemetry.recordStartVerificationConflict("version");
            throw new TicketVersionConflictException(mismatch.currentVersion());
        }
        if (outcome instanceof TicketVerificationStartUpdateOutcome.NotAssigned) {
            telemetry.recordStartVerificationConflict("not_assigned");
            throw new TicketNotAssignedException();
        }
        if (outcome instanceof TicketVerificationStartUpdateOutcome.InvalidState invalidState) {
            telemetry.recordStartVerificationConflict("state");
            throw new InvalidStatusTransitionException(invalidState.currentStatus(), started.newStatus());
        }
        if (outcome instanceof TicketVerificationStartUpdateOutcome.AttemptAlreadyActive) {
            telemetry.recordStartVerificationConflict("attempt_already_active");
            throw new VerificationAttemptAlreadyActiveException();
        }
    }

    private record Reservation(UUID idempotencyRecordId, Instant now, StartVerificationResult replayed) {
    }

    private Reservation reserveIdempotency(StartVerificationCommand command) {
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

    private Map<String, Object> canonicalBody(StartVerificationCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticketId", command.ticketId().toString());
        body.put("toolResultId", command.toolResultId());
        body.put("verificationType", command.verificationType());
        body.put("reason", command.reason());
        body.put("expectedVersion", command.expectedVersion());
        return body;
    }

    private void completeIdempotency(UUID idempotencyRecordId, TicketId ticketId, StartVerificationResult result, Instant now) {
        idempotencyRepository.complete(idempotencyRecordId, new IdempotencyCompletion(
            "TICKET", ticketId.toString(), SUCCESS_HTTP_STATUS, serializeResult(result), now
        ));
    }

    private String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }

    private String serializeResult(StartVerificationResult result) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ticketId", result.ticketId().value().toString());
            body.put("verificationId", result.verificationId());
            body.put("resolutionCycleId", result.resolutionCycleId().toString());
            body.put("workflowId", result.workflowId());
            body.put("toolResultId", result.toolResultId());
            body.put("attemptNumber", result.attemptNumber());
            body.put("verificationType", result.verificationType());
            body.put("previousStatus", result.previousStatus().name());
            body.put("status", result.status().name());
            body.put("startedBy", result.startedBy());
            body.put("startedAt", result.startedAt().toString());
            body.put("version", result.version());
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize start verification result", e);
        }
    }

    private StartVerificationResult deserializeResult(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.readValue(json, Map.class);
            return new StartVerificationResult(
                TicketId.of(UUID.fromString((String) body.get("ticketId"))),
                (String) body.get("verificationId"),
                UUID.fromString((String) body.get("resolutionCycleId")),
                (String) body.get("workflowId"),
                (String) body.get("toolResultId"),
                ((Number) body.get("attemptNumber")).intValue(),
                (String) body.get("verificationType"),
                TicketStatus.valueOf((String) body.get("previousStatus")),
                TicketStatus.valueOf((String) body.get("status")),
                (String) body.get("startedBy"),
                Instant.parse((String) body.get("startedAt")),
                ((Number) body.get("version")).longValue(),
                true
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored idempotency response", e);
        }
    }
}
