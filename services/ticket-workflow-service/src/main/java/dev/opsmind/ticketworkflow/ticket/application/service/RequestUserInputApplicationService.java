package dev.opsmind.ticketworkflow.ticket.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.RequestUserInputCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.RequestUserInputResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketUserInputRequestedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.QueueAccessDeniedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.RequestInProgressException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketVersionConflictException;
import dev.opsmind.ticketworkflow.ticket.application.exception.UserInputRequestAlreadyOpenException;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyCompletion;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationOutcome;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationRequest;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.RequestHashCalculator;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.TicketStatusHistoryEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.in.RequestUserInputUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.IdempotencyRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketStatusTransitionGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketStatusTransitionGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketUserInputRequestRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketUserInputRequestUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketUserInputRequestUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketUserInputRequested;
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
import java.util.Set;
import java.util.UUID;

/**
 * SPEC-TW-012 §1/domain-rules §1-2: {@code IN_PROGRESS -> WAITING_FOR_USER}
 * with a dedicated open user-input-request row. Mirrors {@code
 * CloseTicketApplicationService}'s orchestration shape, including the
 * status pre-check ordering lesson from SPEC-TW-011 AC-02: a ticket already
 * {@code WAITING_FOR_USER} must fail with the specific {@code
 * USER_INPUT_REQUEST_ALREADY_OPEN} code, not the generic {@code
 * INVALID_STATUS_TRANSITION}. Reuses {@link TicketStatusTransitionGuardPort}
 * (SPEC-TW-009) rather than a new guard shape, since both commands only
 * need the same ticket/queue/assignee projection.
 */
@Service
public class RequestUserInputApplicationService implements RequestUserInputUseCase {

    private static final String OPERATION_ID = "requestUserInput";
    private static final String ROUTE_TEMPLATE = "/api/v1/tickets/{ticketId}/user-input-requests";
    private static final String REQUIRED_SCOPE = "ticket:request-user-input";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(5);
    private static final int SUCCESS_HTTP_STATUS = 201;

    private final TicketStatusTransitionGuardPort guardPort;
    private final TicketUserInputRequestRepository requestRepository;
    private final TicketHistoryWriter historyWriter;
    private final AuditRecordPort auditRecordPort;
    private final OutboxEventRepository outboxEventRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ClockPort clock;
    private final RequestHashCalculator requestHashCalculator;
    private final TicketUserInputRequestedEventMapper eventMapper;
    private final TicketTelemetry telemetry;
    private final ObjectMapper objectMapper;

    public RequestUserInputApplicationService(
        TicketStatusTransitionGuardPort guardPort,
        TicketUserInputRequestRepository requestRepository,
        TicketHistoryWriter historyWriter,
        AuditRecordPort auditRecordPort,
        OutboxEventRepository outboxEventRepository,
        IdempotencyRepository idempotencyRepository,
        ClockPort clock,
        RequestHashCalculator requestHashCalculator,
        TicketUserInputRequestedEventMapper eventMapper,
        TicketTelemetry telemetry,
        ObjectMapper objectMapper
    ) {
        this.guardPort = guardPort;
        this.requestRepository = requestRepository;
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
    public RequestUserInputResult requestUserInput(RequestUserInputCommand command) {
        var timer = telemetry.startRequestUserInputTimer();
        try {
            authorize(command.actor());
            Reservation reservation = reserveIdempotency(command);
            if (reservation.replayed() != null) {
                telemetry.recordRequestUserInputCommand("replay");
                return reservation.replayed();
            }
            Instant now = reservation.now();

            TicketStatusTransitionGuard guard = guardPort.loadGuard(command.ticketId()).orElseThrow(TicketNotFoundException::new);
            checkVersion(guard, command.expectedVersion());
            checkQueueAccess(guard, command.allowedTeamIds());
            checkStatus(guard);

            UUID requestId = UUID.randomUUID();
            TicketUserInputRequested requested = Ticket.requestUserInput(
                command.ticketId(), guard.status(), guard.currentAssigneeId(), guard.version(), requestId,
                command.prompt(), command.requestedFields(), command.expiresAt(),
                command.actor().actorType(), command.actor().subject(), now
            );

            applyUpdate(requested, command);

            historyWriter.append(new TicketStatusHistoryEntry(
                UUID.randomUUID(), command.ticketId(), requested.previousStatus(), requested.newStatus(),
                requested.transitionId(), requested.reasonCode(), command.actor().actorType(), command.actor().subject(),
                command.commandId(), null, null, requested.aggregateVersion(), now
            ));

            String traceId = currentTraceId();
            auditRecordPort.append(new AuditRecordEntry(
                UUID.randomUUID(), "BUSINESS_ACTION", "USER_INPUT_REQUESTED", "ALLOWED",
                command.actor().actorType(), command.actor().subject(), command.actor().clientId(),
                "TICKET", command.ticketId().toString(), guard.displayId().value(),
                requested.previousStatus().name(), requested.newStatus().name(),
                traceId, command.commandId(), "SUCCESS", "INTERNAL", now, null, null
            ));
            outboxEventRepository.append(eventMapper.map(requested, guard.supportQueueId(), traceId, command.correlationId(), command.commandId()));

            RequestUserInputResult result = new RequestUserInputResult(
                command.ticketId(), requested.requestId(), requested.previousStatus(), requested.newStatus(),
                requested.prompt(), requested.requestedById(), requested.requestedAt(), requested.waitingForRequesterSince(),
                requested.aggregateVersion(), false
            );
            completeIdempotency(reservation.idempotencyRecordId(), command.ticketId(), result, now);
            telemetry.recordRequestUserInputCommand("success");
            return result;
        } finally {
            telemetry.stopRequestUserInputTimer(timer);
        }
    }

    private void authorize(ActorContext actor) {
        if (!actor.hasScope(REQUIRED_SCOPE)) {
            telemetry.recordRequestUserInputAuthorizationDenied();
            throw new TicketAuthorizationException(REQUIRED_SCOPE);
        }
    }

    private void checkVersion(TicketStatusTransitionGuard guard, long expectedVersion) {
        if (guard.version() != expectedVersion) {
            telemetry.recordRequestUserInputConflict("version");
            throw new TicketVersionConflictException(guard.version());
        }
    }

    private void checkQueueAccess(TicketStatusTransitionGuard guard, Set<String> allowedTeamIds) {
        if (guard.teamId() == null || !allowedTeamIds.contains(guard.teamId())) {
            telemetry.recordRequestUserInputAuthorizationDenied();
            throw new QueueAccessDeniedException();
        }
    }

    /**
     * SPEC-TW-012 AC-02: a ticket already {@code WAITING_FOR_USER} means an
     * {@code OPEN} request already exists (the invariant this command and
     * SPEC-TW-013's reply/resume command jointly maintain) — that specific
     * case must fail with {@code USER_INPUT_REQUEST_ALREADY_OPEN}, not the
     * generic {@code INVALID_STATUS_TRANSITION} every other wrong status
     * gets.
     */
    private void checkStatus(TicketStatusTransitionGuard guard) {
        if (guard.status() == TicketStatus.WAITING_FOR_USER) {
            telemetry.recordRequestUserInputConflict("already_open");
            throw new UserInputRequestAlreadyOpenException();
        }
        if (guard.status() != TicketStatus.IN_PROGRESS) {
            telemetry.recordRequestUserInputConflict("state");
            throw new InvalidStatusTransitionException(guard.status(), TicketStatus.WAITING_FOR_USER);
        }
    }

    private void applyUpdate(TicketUserInputRequested requested, RequestUserInputCommand command) {
        TicketUserInputRequestUpdateOutcome outcome = requestRepository.applyRequestUserInput(new TicketUserInputRequestUpdate(
            requested.ticketId(), requested.aggregateVersion() - 1, requested.requestId(), requested.prompt(),
            requested.requestedFields(), requested.requestedByType(), requested.requestedById(), requested.requestedAt(),
            requested.resumeStatus(), requested.expiresAt(), command.correlationId(), requested.occurredAt()
        ));
        if (outcome instanceof TicketUserInputRequestUpdateOutcome.TicketMissing) {
            throw new TicketNotFoundException();
        }
        if (outcome instanceof TicketUserInputRequestUpdateOutcome.VersionMismatch mismatch) {
            telemetry.recordRequestUserInputConflict("version");
            throw new TicketVersionConflictException(mismatch.currentVersion());
        }
        if (outcome instanceof TicketUserInputRequestUpdateOutcome.NotAssigned) {
            telemetry.recordRequestUserInputConflict("not_assigned");
            throw new TicketNotAssignedException();
        }
        if (outcome instanceof TicketUserInputRequestUpdateOutcome.InvalidState invalidState) {
            telemetry.recordRequestUserInputConflict("state");
            if (invalidState.currentStatus() == TicketStatus.WAITING_FOR_USER) {
                throw new UserInputRequestAlreadyOpenException();
            }
            throw new InvalidStatusTransitionException(invalidState.currentStatus(), requested.newStatus());
        }
        if (outcome instanceof TicketUserInputRequestUpdateOutcome.RequestAlreadyOpen) {
            telemetry.recordRequestUserInputConflict("already_open");
            throw new UserInputRequestAlreadyOpenException();
        }
    }

    private record Reservation(UUID idempotencyRecordId, Instant now, RequestUserInputResult replayed) {
    }

    private Reservation reserveIdempotency(RequestUserInputCommand command) {
        String actorScope = "user:" + command.actor().subject() + ":" + command.ticketId() + ":" + OPERATION_ID;
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

    private Map<String, Object> canonicalBody(RequestUserInputCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticketId", command.ticketId().toString());
        body.put("prompt", command.prompt());
        body.put("requestedFields", command.requestedFields());
        body.put("expiresAt", command.expiresAt() == null ? null : command.expiresAt().toString());
        body.put("expectedVersion", command.expectedVersion());
        return body;
    }

    private void completeIdempotency(UUID idempotencyRecordId, TicketId ticketId, RequestUserInputResult result, Instant now) {
        idempotencyRepository.complete(idempotencyRecordId, new IdempotencyCompletion(
            "TICKET", ticketId.toString(), SUCCESS_HTTP_STATUS, serializeResult(result), now
        ));
    }

    private String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }

    private String serializeResult(RequestUserInputResult result) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ticketId", result.ticketId().value().toString());
            body.put("requestId", result.requestId().toString());
            body.put("previousStatus", result.previousStatus().name());
            body.put("status", result.status().name());
            body.put("prompt", result.prompt());
            body.put("requestedBy", result.requestedBy());
            body.put("requestedAt", result.requestedAt().toString());
            body.put("waitingForRequesterSince", result.waitingForRequesterSince().toString());
            body.put("version", result.version());
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize request user input result", e);
        }
    }

    private RequestUserInputResult deserializeResult(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.readValue(json, Map.class);
            return new RequestUserInputResult(
                TicketId.of(UUID.fromString((String) body.get("ticketId"))),
                UUID.fromString((String) body.get("requestId")),
                TicketStatus.valueOf((String) body.get("previousStatus")),
                TicketStatus.valueOf((String) body.get("status")),
                (String) body.get("prompt"),
                (String) body.get("requestedBy"),
                Instant.parse((String) body.get("requestedAt")),
                Instant.parse((String) body.get("waitingForRequesterSince")),
                ((Number) body.get("version")).longValue(),
                true
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored idempotency response", e);
        }
    }
}
