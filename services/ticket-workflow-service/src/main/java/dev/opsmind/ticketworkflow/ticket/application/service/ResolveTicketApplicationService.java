package dev.opsmind.ticketworkflow.ticket.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.configuration.TicketWorkflowProperties;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.ResolveTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ResolveTicketResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketResolvedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.QueueAccessDeniedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.RequestInProgressException;
import dev.opsmind.ticketworkflow.ticket.application.exception.ResolutionCycleAlreadyCompletedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.ResolutionCycleNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketVersionConflictException;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyCompletion;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationOutcome;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationRequest;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.RequestHashCalculator;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.TicketStatusHistoryEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.in.ResolveTicketUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.IdempotencyRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketResolveGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketResolveGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketResolveRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketResolveUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketResolveUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketResolved;
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
 * SPEC-TW-010: {@code IN_PROGRESS -> RESOLVED} with structured resolution
 * data. Mirrors {@link TransitionTicketStatusApplicationService}'s
 * orchestration shape (domain-rules §11's handler order), with two
 * additions: a resolution-cycle guard check (step 7) between version and
 * Aggregate invocation, and the resolution-cycle completion write inside the
 * same repository call as the ticket-row update (persistence §7).
 */
@Service
public class ResolveTicketApplicationService implements ResolveTicketUseCase {

    private static final String OPERATION_ID = "resolveTicket";
    private static final String ROUTE_TEMPLATE = "/api/v1/tickets/{ticketId}/resolution";
    private static final String REQUIRED_SCOPE = "ticket:resolve";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(5);
    private static final int SUCCESS_HTTP_STATUS = 200;

    private final TicketResolveGuardPort guardPort;
    private final TicketResolveRepository resolveRepository;
    private final TicketHistoryWriter historyWriter;
    private final AuditRecordPort auditRecordPort;
    private final OutboxEventRepository outboxEventRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ClockPort clock;
    private final RequestHashCalculator requestHashCalculator;
    private final TicketResolvedEventMapper eventMapper;
    private final TicketTelemetry telemetry;
    private final ObjectMapper objectMapper;
    private final TicketWorkflowProperties properties;

    public ResolveTicketApplicationService(
        TicketResolveGuardPort guardPort,
        TicketResolveRepository resolveRepository,
        TicketHistoryWriter historyWriter,
        AuditRecordPort auditRecordPort,
        OutboxEventRepository outboxEventRepository,
        IdempotencyRepository idempotencyRepository,
        ClockPort clock,
        RequestHashCalculator requestHashCalculator,
        TicketResolvedEventMapper eventMapper,
        TicketTelemetry telemetry,
        ObjectMapper objectMapper,
        TicketWorkflowProperties properties
    ) {
        this.guardPort = guardPort;
        this.resolveRepository = resolveRepository;
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
    public ResolveTicketResult resolve(ResolveTicketCommand command) {
        var timer = telemetry.startResolutionTimer();
        try {
            authorize(command.actor());
            Reservation reservation = reserveIdempotency(command);
            if (reservation.replayed() != null) {
                telemetry.recordResolutionCommand(command.resolutionCode().name(), "replay");
                return reservation.replayed();
            }
            Instant now = reservation.now();

            TicketResolveGuard guard = guardPort.loadGuard(command.ticketId()).orElseThrow(TicketNotFoundException::new);
            checkVersion(guard, command.expectedVersion());
            checkQueueAccess(guard, command.allowedTeamIds());
            checkResolutionCycle(guard);

            Instant autoCloseDueAt = now.plus(properties.sla().autoCloseDue());

            TicketResolved resolved = Ticket.resolve(
                command.ticketId(), guard.status(), guard.currentAssigneeId(), guard.currentResolutionCycleId(),
                guard.version(), command.resolutionCode(), command.resolutionSummary(), autoCloseDueAt,
                command.actor().actorType(), command.actor().subject(), now
            );

            applyUpdate(resolved);

            historyWriter.append(new TicketStatusHistoryEntry(
                UUID.randomUUID(), command.ticketId(), resolved.previousStatus(), resolved.newStatus(),
                resolved.transitionId(), resolved.reasonCode(), command.actor().actorType(), command.actor().subject(),
                command.commandId(), null, null, resolved.aggregateVersion(), now
            ));

            String traceId = currentTraceId();
            auditRecordPort.append(new AuditRecordEntry(
                UUID.randomUUID(), "BUSINESS_ACTION", "TICKET_RESOLVED", "ALLOWED",
                command.actor().actorType(), command.actor().subject(), command.actor().clientId(),
                "TICKET", command.ticketId().toString(), guard.displayId().value(),
                resolved.previousStatus().name(), resolved.newStatus().name(),
                traceId, command.commandId(), "SUCCESS", "INTERNAL", now, null, null
            ));
            outboxEventRepository.append(eventMapper.map(resolved, guard.supportQueueId(), traceId, command.correlationId(), command.commandId()));

            ResolveTicketResult result = new ResolveTicketResult(
                command.ticketId(), resolved.previousStatus(), resolved.newStatus(), resolved.resolutionCode(),
                resolved.resolutionSummary(), resolved.resolvedById(), resolved.resolvedAt(), resolved.resolutionCycleId(),
                resolved.autoCloseDueAt(), resolved.aggregateVersion(), false
            );
            completeIdempotency(reservation.idempotencyRecordId(), command.ticketId(), result, now);
            telemetry.recordResolutionCommand(command.resolutionCode().name(), "success");
            return result;
        } finally {
            telemetry.stopResolutionTimer(timer);
        }
    }

    private void authorize(ActorContext actor) {
        if (!actor.hasScope(REQUIRED_SCOPE)) {
            telemetry.recordResolutionAuthorizationDenied();
            throw new TicketAuthorizationException(REQUIRED_SCOPE);
        }
    }

    private void checkVersion(TicketResolveGuard guard, long expectedVersion) {
        if (guard.version() != expectedVersion) {
            telemetry.recordResolutionConflict("version");
            throw new TicketVersionConflictException(guard.version());
        }
    }

    private void checkQueueAccess(TicketResolveGuard guard, java.util.Set<String> allowedTeamIds) {
        if (guard.teamId() == null || !allowedTeamIds.contains(guard.teamId())) {
            telemetry.recordResolutionAuthorizationDenied();
            throw new QueueAccessDeniedException();
        }
    }

    private void checkResolutionCycle(TicketResolveGuard guard) {
        if (guard.currentResolutionCycleId() == null || guard.resolutionCycleStatus() == null) {
            telemetry.recordResolutionConflict("cycle_not_found");
            throw new ResolutionCycleNotFoundException();
        }
        if (guard.resolutionCycleStatus() != ResolutionCycleStatus.ACTIVE) {
            telemetry.recordResolutionConflict("cycle_already_completed");
            throw new ResolutionCycleAlreadyCompletedException();
        }
    }

    private void applyUpdate(TicketResolved resolved) {
        TicketResolveUpdateOutcome outcome = resolveRepository.applyResolution(new TicketResolveUpdate(
            resolved.ticketId(), resolved.aggregateVersion() - 1, resolved.resolutionCycleId(), resolved.resolutionCode(),
            resolved.resolutionSummary(), resolved.resolvedByType(), resolved.resolvedById(), resolved.resolvedAt(),
            resolved.autoCloseDueAt(), resolved.occurredAt()
        ));
        if (outcome instanceof TicketResolveUpdateOutcome.TicketMissing) {
            throw new TicketNotFoundException();
        }
        if (outcome instanceof TicketResolveUpdateOutcome.VersionMismatch mismatch) {
            telemetry.recordResolutionConflict("version");
            throw new TicketVersionConflictException(mismatch.currentVersion());
        }
        if (outcome instanceof TicketResolveUpdateOutcome.NotAssigned) {
            telemetry.recordResolutionConflict("not_assigned");
            throw new TicketNotAssignedException();
        }
        if (outcome instanceof TicketResolveUpdateOutcome.InvalidState invalidState) {
            telemetry.recordResolutionConflict("state");
            throw new InvalidStatusTransitionException(invalidState.currentStatus(), resolved.newStatus());
        }
        if (outcome instanceof TicketResolveUpdateOutcome.ResolutionCycleConflict) {
            telemetry.recordResolutionConflict("cycle_already_completed");
            throw new ResolutionCycleAlreadyCompletedException();
        }
    }

    private record Reservation(UUID idempotencyRecordId, Instant now, ResolveTicketResult replayed) {
    }

    private Reservation reserveIdempotency(ResolveTicketCommand command) {
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

    private Map<String, Object> canonicalBody(ResolveTicketCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticketId", command.ticketId().toString());
        body.put("resolutionCode", command.resolutionCode().name());
        body.put("resolutionSummary", command.resolutionSummary());
        body.put("expectedVersion", command.expectedVersion());
        return body;
    }

    private void completeIdempotency(UUID idempotencyRecordId, TicketId ticketId, ResolveTicketResult result, Instant now) {
        idempotencyRepository.complete(idempotencyRecordId, new IdempotencyCompletion(
            "TICKET", ticketId.toString(), SUCCESS_HTTP_STATUS, serializeResult(result), now
        ));
    }

    private String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }

    private String serializeResult(ResolveTicketResult result) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ticketId", result.ticketId().value().toString());
            body.put("previousStatus", result.previousStatus().name());
            body.put("status", result.status().name());
            body.put("resolutionCode", result.resolutionCode().name());
            body.put("resolutionSummary", result.resolutionSummary());
            body.put("resolvedBy", result.resolvedBy());
            body.put("resolvedAt", result.resolvedAt().toString());
            body.put("resolutionCycleId", result.resolutionCycleId().toString());
            body.put("autoCloseDueAt", result.autoCloseDueAt() == null ? null : result.autoCloseDueAt().toString());
            body.put("version", result.version());
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize resolve ticket result", e);
        }
    }

    private ResolveTicketResult deserializeResult(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.readValue(json, Map.class);
            String autoCloseDueAtRaw = (String) body.get("autoCloseDueAt");
            return new ResolveTicketResult(
                TicketId.of(UUID.fromString((String) body.get("ticketId"))),
                TicketStatus.valueOf((String) body.get("previousStatus")),
                TicketStatus.valueOf((String) body.get("status")),
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
