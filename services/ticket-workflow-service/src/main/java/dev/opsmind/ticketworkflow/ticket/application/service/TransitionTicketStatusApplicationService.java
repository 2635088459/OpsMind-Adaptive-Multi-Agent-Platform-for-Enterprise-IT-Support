package dev.opsmind.ticketworkflow.ticket.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.TransitionTicketStatusCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.TransitionTicketStatusResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketStatusTransitionEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.QueueAccessDeniedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.RequestInProgressException;
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
import dev.opsmind.ticketworkflow.ticket.application.port.in.TransitionTicketStatusUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.IdempotencyRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketStatusTransitionGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketStatusTransitionGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketStatusTransitionRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketStatusTransitionUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketStatusTransitionUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketStatusChanged;
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

@Service
public class TransitionTicketStatusApplicationService implements TransitionTicketStatusUseCase {

    private static final String OPERATION_ID = "transitionTicketStatus";
    private static final String ROUTE_TEMPLATE = "/api/v1/tickets/{ticketId}/status-transitions";
    private static final String REQUIRED_SCOPE = "ticket:transition";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(5);
    private static final int SUCCESS_HTTP_STATUS = 200;

    private final TicketStatusTransitionGuardPort guardPort;
    private final TicketStatusTransitionRepository transitionRepository;
    private final TicketHistoryWriter historyWriter;
    private final AuditRecordPort auditRecordPort;
    private final OutboxEventRepository outboxEventRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ClockPort clock;
    private final RequestHashCalculator requestHashCalculator;
    private final TicketStatusTransitionEventMapper eventMapper;
    private final TicketTelemetry telemetry;
    private final ObjectMapper objectMapper;

    public TransitionTicketStatusApplicationService(
        TicketStatusTransitionGuardPort guardPort,
        TicketStatusTransitionRepository transitionRepository,
        TicketHistoryWriter historyWriter,
        AuditRecordPort auditRecordPort,
        OutboxEventRepository outboxEventRepository,
        IdempotencyRepository idempotencyRepository,
        ClockPort clock,
        RequestHashCalculator requestHashCalculator,
        TicketStatusTransitionEventMapper eventMapper,
        TicketTelemetry telemetry,
        ObjectMapper objectMapper
    ) {
        this.guardPort = guardPort;
        this.transitionRepository = transitionRepository;
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
    public TransitionTicketStatusResult transition(TransitionTicketStatusCommand command) {
        var timer = telemetry.startStatusTransitionTimer();
        try {
            authorize(command.actor());
            Reservation reservation = reserveIdempotency(command);
            if (reservation.replayed() != null) {
                telemetry.recordStatusTransitionCommand(command.targetStatus().name(), "replay");
                return reservation.replayed();
            }
            Instant now = reservation.now();

            TicketStatusTransitionGuard guard = guardPort.loadGuard(command.ticketId()).orElseThrow(TicketNotFoundException::new);
            checkVersion(guard, command.expectedVersion());
            checkQueueAccess(guard, command.allowedTeamIds());

            TicketStatusChanged changed = Ticket.transitionStatus(
                command.ticketId(), guard.status(), guard.currentAssigneeId(), guard.version(), command.targetStatus(),
                command.actor().actorType(), command.actor().subject(), command.reason(),
                command.waitingForRequesterSince(), command.approvalReference(), now
            );

            applyUpdate(changed);

            historyWriter.append(new TicketStatusHistoryEntry(
                UUID.randomUUID(), command.ticketId(), changed.previousStatus(), changed.newStatus(),
                changed.transitionId(), changed.reasonCode(), command.actor().actorType(), command.actor().subject(),
                command.commandId(), null, null, changed.aggregateVersion(), now
            ));

            String traceId = currentTraceId();
            auditRecordPort.append(new AuditRecordEntry(
                UUID.randomUUID(), "BUSINESS_ACTION", "TICKET_STATUS_CHANGED", "ALLOWED",
                command.actor().actorType(), command.actor().subject(), command.actor().clientId(),
                "TICKET", command.ticketId().toString(), guard.displayId().value(),
                changed.previousStatus().name(), changed.newStatus().name(),
                traceId, command.commandId(), "SUCCESS", "INTERNAL", now, null, null
            ));
            outboxEventRepository.append(eventMapper.map(changed, guard.supportQueueId(), traceId, command.correlationId(), command.commandId()));

            TransitionTicketStatusResult result = new TransitionTicketStatusResult(
                command.ticketId(), changed.previousStatus(), changed.newStatus(), command.reason(),
                changed.waitingForRequesterSince(), changed.approvalReference(), now, changed.aggregateVersion(), false
            );
            completeIdempotency(reservation.idempotencyRecordId(), command.ticketId(), result, now);
            telemetry.recordStatusTransitionCommand(changed.transitionId(), "success");
            return result;
        } finally {
            telemetry.stopStatusTransitionTimer(timer);
        }
    }

    private void authorize(ActorContext actor) {
        if (!actor.hasScope(REQUIRED_SCOPE)) {
            telemetry.recordStatusTransitionAuthorizationDenied();
            throw new TicketAuthorizationException(REQUIRED_SCOPE);
        }
    }

    private void checkVersion(TicketStatusTransitionGuard guard, long expectedVersion) {
        if (guard.version() != expectedVersion) {
            telemetry.recordStatusTransitionConflict("version");
            throw new TicketVersionConflictException(guard.version());
        }
    }

    private void checkQueueAccess(TicketStatusTransitionGuard guard, Set<String> allowedTeamIds) {
        if (guard.teamId() == null || !allowedTeamIds.contains(guard.teamId())) {
            telemetry.recordStatusTransitionAuthorizationDenied();
            throw new QueueAccessDeniedException();
        }
    }

    private void applyUpdate(TicketStatusChanged changed) {
        TicketStatusTransitionUpdateOutcome outcome = transitionRepository.applyTransition(new TicketStatusTransitionUpdate(
            changed.ticketId(), changed.aggregateVersion() - 1, changed.previousStatus(), changed.newStatus(),
            changed.waitingForRequesterSince(), changed.approvalReference(), changed.occurredAt()
        ));
        if (outcome instanceof TicketStatusTransitionUpdateOutcome.TicketMissing) {
            throw new TicketNotFoundException();
        }
        if (outcome instanceof TicketStatusTransitionUpdateOutcome.VersionMismatch mismatch) {
            telemetry.recordStatusTransitionConflict("version");
            throw new TicketVersionConflictException(mismatch.currentVersion());
        }
        if (outcome instanceof TicketStatusTransitionUpdateOutcome.NotAssigned) {
            telemetry.recordStatusTransitionConflict("not_assigned");
            throw new TicketNotAssignedException();
        }
        if (outcome instanceof TicketStatusTransitionUpdateOutcome.InvalidState invalidState) {
            telemetry.recordStatusTransitionConflict("state");
            throw new InvalidStatusTransitionException(invalidState.currentStatus(), changed.newStatus());
        }
    }

    private record Reservation(UUID idempotencyRecordId, Instant now, TransitionTicketStatusResult replayed) {
    }

    private Reservation reserveIdempotency(TransitionTicketStatusCommand command) {
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

    private Map<String, Object> canonicalBody(TransitionTicketStatusCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticketId", command.ticketId().toString());
        body.put("targetStatus", command.targetStatus().name());
        body.put("reason", command.reason());
        body.put("waitingForRequesterSince", command.waitingForRequesterSince() == null ? null : command.waitingForRequesterSince().toString());
        body.put("approvalReference", command.approvalReference());
        body.put("expectedVersion", command.expectedVersion());
        return body;
    }

    private void completeIdempotency(UUID idempotencyRecordId, TicketId ticketId, TransitionTicketStatusResult result, Instant now) {
        idempotencyRepository.complete(idempotencyRecordId, new IdempotencyCompletion(
            "TICKET", ticketId.toString(), SUCCESS_HTTP_STATUS, serializeResult(result), now
        ));
    }

    private String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }

    private String serializeResult(TransitionTicketStatusResult result) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ticketId", result.ticketId().value().toString());
            body.put("previousStatus", result.previousStatus().name());
            body.put("status", result.status().name());
            body.put("reason", result.reason());
            body.put("waitingForRequesterSince", result.waitingForRequesterSince() == null ? null : result.waitingForRequesterSince().toString());
            body.put("approvalReference", result.approvalReference());
            body.put("transitionedAt", result.transitionedAt().toString());
            body.put("version", result.version());
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize status transition result", e);
        }
    }

    private TransitionTicketStatusResult deserializeResult(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.readValue(json, Map.class);
            String waitingSinceRaw = (String) body.get("waitingForRequesterSince");
            return new TransitionTicketStatusResult(
                TicketId.of(UUID.fromString((String) body.get("ticketId"))),
                TicketStatus.valueOf((String) body.get("previousStatus")),
                TicketStatus.valueOf((String) body.get("status")),
                (String) body.get("reason"),
                waitingSinceRaw == null ? null : Instant.parse(waitingSinceRaw),
                (String) body.get("approvalReference"),
                Instant.parse((String) body.get("transitionedAt")),
                ((Number) body.get("version")).longValue(),
                true
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored idempotency response", e);
        }
    }
}
