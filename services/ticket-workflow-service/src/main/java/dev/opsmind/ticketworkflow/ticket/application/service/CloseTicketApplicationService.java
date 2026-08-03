package dev.opsmind.ticketworkflow.ticket.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.CloseTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.CloseTicketResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketClosedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.QueueAccessDeniedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.RequestInProgressException;
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
import dev.opsmind.ticketworkflow.ticket.application.port.in.CloseTicketUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.IdempotencyRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketCloseRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketCloseReopenGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketCloseReopenGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketCloseUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketCloseUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketClosed;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidStatusTransitionException;
import dev.opsmind.ticketworkflow.ticket.domain.model.Ticket;
import dev.opsmind.ticketworkflow.ticket.domain.value.CloseReasonCode;
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
import java.util.Set;
import java.util.UUID;

/**
 * SPEC-TW-011 §2/domain-rules §2: {@code RESOLVED -> CLOSED}. Mirrors
 * {@code ResolveTicketApplicationService}'s orchestration shape exactly,
 * with the resolution-cycle guard requiring {@code RESOLVED} (not {@code
 * ACTIVE}) before Close may proceed.
 */
@Service
public class CloseTicketApplicationService implements CloseTicketUseCase {

    private static final String OPERATION_ID = "closeTicket";
    private static final String ROUTE_TEMPLATE = "/api/v1/tickets/{ticketId}/closure";
    private static final String REQUIRED_SCOPE = "ticket:close";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(5);
    private static final int SUCCESS_HTTP_STATUS = 200;

    private final TicketCloseReopenGuardPort guardPort;
    private final TicketCloseRepository closeRepository;
    private final TicketHistoryWriter historyWriter;
    private final AuditRecordPort auditRecordPort;
    private final OutboxEventRepository outboxEventRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ClockPort clock;
    private final RequestHashCalculator requestHashCalculator;
    private final TicketClosedEventMapper eventMapper;
    private final TicketTelemetry telemetry;
    private final ObjectMapper objectMapper;

    public CloseTicketApplicationService(
        TicketCloseReopenGuardPort guardPort,
        TicketCloseRepository closeRepository,
        TicketHistoryWriter historyWriter,
        AuditRecordPort auditRecordPort,
        OutboxEventRepository outboxEventRepository,
        IdempotencyRepository idempotencyRepository,
        ClockPort clock,
        RequestHashCalculator requestHashCalculator,
        TicketClosedEventMapper eventMapper,
        TicketTelemetry telemetry,
        ObjectMapper objectMapper
    ) {
        this.guardPort = guardPort;
        this.closeRepository = closeRepository;
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
    public CloseTicketResult close(CloseTicketCommand command) {
        var timer = telemetry.startCloseTimer();
        try {
            authorize(command.actor());
            Reservation reservation = reserveIdempotency(command);
            if (reservation.replayed() != null) {
                telemetry.recordCloseCommand("replay");
                return reservation.replayed();
            }
            Instant now = reservation.now();

            TicketCloseReopenGuard guard = guardPort.loadGuard(command.ticketId()).orElseThrow(TicketNotFoundException::new);
            checkVersion(guard, command.expectedVersion());
            checkQueueAccess(guard, command.allowedTeamIds());
            checkStatus(guard);
            checkResolutionCycle(guard);

            TicketClosed closed = Ticket.close(
                command.ticketId(), guard.status(), guard.currentAssigneeId(), guard.currentResolutionCycleId(),
                guard.version(), command.closeReasonCode(), command.closeReason(),
                command.actor().actorType(), command.actor().subject(), now
            );

            applyUpdate(closed);

            historyWriter.append(new TicketStatusHistoryEntry(
                UUID.randomUUID(), command.ticketId(), closed.previousStatus(), closed.newStatus(),
                closed.transitionId(), closed.reasonCode(), command.actor().actorType(), command.actor().subject(),
                command.commandId(), null, null, closed.aggregateVersion(), now
            ));

            String traceId = currentTraceId();
            auditRecordPort.append(new AuditRecordEntry(
                UUID.randomUUID(), "BUSINESS_ACTION", "TICKET_CLOSED", "ALLOWED",
                command.actor().actorType(), command.actor().subject(), command.actor().clientId(),
                "TICKET", command.ticketId().toString(), guard.displayId().value(),
                closed.previousStatus().name(), closed.newStatus().name(),
                traceId, command.commandId(), "SUCCESS", "INTERNAL", now, null, null
            ));
            outboxEventRepository.append(eventMapper.map(closed, guard.supportQueueId(), traceId, command.correlationId(), command.commandId()));

            CloseTicketResult result = new CloseTicketResult(
                command.ticketId(), closed.previousStatus(), closed.newStatus(), closed.closeReasonCode(),
                closed.closedById(), closed.closedAt(), closed.resolutionCycleId(), closed.aggregateVersion(), false
            );
            completeIdempotency(reservation.idempotencyRecordId(), command.ticketId(), result, now);
            telemetry.recordCloseCommand("success");
            return result;
        } finally {
            telemetry.stopCloseTimer(timer);
        }
    }

    private void authorize(ActorContext actor) {
        if (!actor.hasScope(REQUIRED_SCOPE)) {
            telemetry.recordCloseAuthorizationDenied();
            throw new TicketAuthorizationException(REQUIRED_SCOPE);
        }
    }

    private void checkVersion(TicketCloseReopenGuard guard, long expectedVersion) {
        if (guard.version() != expectedVersion) {
            telemetry.recordCloseConflict("version");
            throw new TicketVersionConflictException(guard.version());
        }
    }

    private void checkQueueAccess(TicketCloseReopenGuard guard, Set<String> allowedTeamIds) {
        if (guard.teamId() == null || !allowedTeamIds.contains(guard.teamId())) {
            telemetry.recordCloseAuthorizationDenied();
            throw new QueueAccessDeniedException();
        }
    }

    /**
     * SPEC-TW-011 AC-02: a non-{@code RESOLVED} ticket must fail with
     * {@code INVALID_STATUS_TRANSITION}, not the resolution-cycle guard
     * below — every non-{@code RESOLVED}/{@code CLOSED} status leaves the
     * current cycle {@code ACTIVE}, which {@link #checkResolutionCycle}
     * would otherwise misreport as {@code RESOLUTION_CYCLE_NOT_FOUND}.
     */
    private void checkStatus(TicketCloseReopenGuard guard) {
        if (guard.status() != TicketStatus.RESOLVED) {
            telemetry.recordCloseConflict("state");
            throw new InvalidStatusTransitionException(guard.status(), TicketStatus.CLOSED);
        }
    }

    private void checkResolutionCycle(TicketCloseReopenGuard guard) {
        if (guard.currentResolutionCycleId() == null || guard.resolutionCycleStatus() != ResolutionCycleStatus.RESOLVED) {
            telemetry.recordCloseConflict("cycle_not_resolved");
            throw new ResolutionCycleNotFoundException();
        }
    }

    private void applyUpdate(TicketClosed closed) {
        TicketCloseUpdateOutcome outcome = closeRepository.applyClose(new TicketCloseUpdate(
            closed.ticketId(), closed.aggregateVersion() - 1, closed.resolutionCycleId(), closed.closeReasonCode(),
            closed.closeReason(), closed.closedByType(), closed.closedById(), closed.closedAt(), closed.occurredAt()
        ));
        if (outcome instanceof TicketCloseUpdateOutcome.TicketMissing) {
            throw new TicketNotFoundException();
        }
        if (outcome instanceof TicketCloseUpdateOutcome.VersionMismatch mismatch) {
            telemetry.recordCloseConflict("version");
            throw new TicketVersionConflictException(mismatch.currentVersion());
        }
        if (outcome instanceof TicketCloseUpdateOutcome.InvalidState invalidState) {
            telemetry.recordCloseConflict("state");
            throw new InvalidStatusTransitionException(invalidState.currentStatus(), closed.newStatus());
        }
        if (outcome instanceof TicketCloseUpdateOutcome.ResolutionCycleConflict) {
            telemetry.recordCloseConflict("cycle_not_resolved");
            throw new ResolutionCycleNotFoundException();
        }
    }

    private record Reservation(UUID idempotencyRecordId, Instant now, CloseTicketResult replayed) {
    }

    private Reservation reserveIdempotency(CloseTicketCommand command) {
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

    private Map<String, Object> canonicalBody(CloseTicketCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticketId", command.ticketId().toString());
        body.put("closeReasonCode", command.closeReasonCode().name());
        body.put("closeReason", command.closeReason());
        body.put("expectedVersion", command.expectedVersion());
        return body;
    }

    private void completeIdempotency(UUID idempotencyRecordId, TicketId ticketId, CloseTicketResult result, Instant now) {
        idempotencyRepository.complete(idempotencyRecordId, new IdempotencyCompletion(
            "TICKET", ticketId.toString(), SUCCESS_HTTP_STATUS, serializeResult(result), now
        ));
    }

    private String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }

    private String serializeResult(CloseTicketResult result) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ticketId", result.ticketId().value().toString());
            body.put("previousStatus", result.previousStatus().name());
            body.put("status", result.status().name());
            body.put("closeReasonCode", result.closeReasonCode().name());
            body.put("closedBy", result.closedBy());
            body.put("closedAt", result.closedAt().toString());
            body.put("resolutionCycleId", result.resolutionCycleId().toString());
            body.put("version", result.version());
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize close ticket result", e);
        }
    }

    private CloseTicketResult deserializeResult(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.readValue(json, Map.class);
            return new CloseTicketResult(
                TicketId.of(UUID.fromString((String) body.get("ticketId"))),
                TicketStatus.valueOf((String) body.get("previousStatus")),
                TicketStatus.valueOf((String) body.get("status")),
                CloseReasonCode.valueOf((String) body.get("closeReasonCode")),
                (String) body.get("closedBy"),
                Instant.parse((String) body.get("closedAt")),
                UUID.fromString((String) body.get("resolutionCycleId")),
                ((Number) body.get("version")).longValue(),
                true
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored idempotency response", e);
        }
    }
}
