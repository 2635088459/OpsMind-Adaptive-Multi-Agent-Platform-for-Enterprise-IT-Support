package dev.opsmind.ticketworkflow.ticket.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.ReopenTicketResult;
import dev.opsmind.ticketworkflow.ticket.application.command.RequesterReopenTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketReopenedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
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
import dev.opsmind.ticketworkflow.ticket.application.port.in.RequesterReopenTicketUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.IdempotencyRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SupportAgentDirectoryPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SupportAgentRecord;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketReopenRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketReopenUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketReopenUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketRequesterReopenGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketRequesterReopenGuardPort;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketReopened;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidTicketStateException;
import dev.opsmind.ticketworkflow.ticket.domain.model.Ticket;
import dev.opsmind.ticketworkflow.ticket.domain.value.OwnershipStatus;
import dev.opsmind.ticketworkflow.ticket.domain.value.ReopenReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SPEC-TW-028 §1/domain-rules: {@code RESOLVED|CLOSED -> IN_PROGRESS}.
 * Mirrors {@code ReopenTicketApplicationService}'s (SPEC-TW-011)
 * orchestration shape almost exactly — same domain call ({@code
 * Ticket.reopen(...)}), same write port ({@link TicketReopenRepository}),
 * same published event ({@code ticket.reopened.v1}, reused unchanged, per
 * SPEC-TW-028's own event-contract) — with the authorization step being the
 * one deliberate difference, following {@code
 * ConfirmResolutionApplicationService}'s (SPEC-TW-026) pattern instead:
 * domain-rules' "Command actor: requester or authorized support actor"
 * allows two distinct paths instead of Reopen's single IT-support-with-
 * queue-membership one — {@link #authorize} requires the {@code
 * REQUIRED_SCOPE} either way, then {@link #checkOwnership} additionally
 * requires an {@code EMPLOYEE} actor to be the ticket's own requester (no
 * Support Queue membership check for either path).
 */
@Service
public class RequesterReopenTicketApplicationService implements RequesterReopenTicketUseCase {

    private static final String OPERATION_ID = "requesterReopenTicket";
    private static final String ROUTE_TEMPLATE = "/api/v1/tickets/{ticketId}/reopen-request";
    private static final String REQUIRED_SCOPE = "ticket:reopen-request";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(5);
    private static final int SUCCESS_HTTP_STATUS = 200;

    private final TicketRequesterReopenGuardPort guardPort;
    private final TicketReopenRepository reopenRepository;
    private final SupportAgentDirectoryPort agentDirectoryPort;
    private final TicketHistoryWriter historyWriter;
    private final AuditRecordPort auditRecordPort;
    private final OutboxEventRepository outboxEventRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ClockPort clock;
    private final RequestHashCalculator requestHashCalculator;
    private final TicketReopenedEventMapper eventMapper;
    private final TicketTelemetry telemetry;
    private final ObjectMapper objectMapper;

    public RequesterReopenTicketApplicationService(
        TicketRequesterReopenGuardPort guardPort,
        TicketReopenRepository reopenRepository,
        SupportAgentDirectoryPort agentDirectoryPort,
        TicketHistoryWriter historyWriter,
        AuditRecordPort auditRecordPort,
        OutboxEventRepository outboxEventRepository,
        IdempotencyRepository idempotencyRepository,
        ClockPort clock,
        RequestHashCalculator requestHashCalculator,
        TicketReopenedEventMapper eventMapper,
        TicketTelemetry telemetry,
        ObjectMapper objectMapper
    ) {
        this.guardPort = guardPort;
        this.reopenRepository = reopenRepository;
        this.agentDirectoryPort = agentDirectoryPort;
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
    public ReopenTicketResult reopen(RequesterReopenTicketCommand command) {
        var timer = telemetry.startRequesterReopenTimer();
        try {
            authorize(command.actor());
            Reservation reservation = reserveIdempotency(command);
            if (reservation.replayed() != null) {
                telemetry.recordRequesterReopenCommand("replay");
                return reservation.replayed();
            }
            Instant now = reservation.now();

            TicketRequesterReopenGuard guard = guardPort.loadGuard(command.ticketId()).orElseThrow(TicketNotFoundException::new);
            checkOwnership(guard, command.actor());
            checkVersion(guard, command.expectedVersion());
            checkResolutionCycle(guard);

            OwnershipStatus ownershipStatus = resolveOwnershipStatus(guard.currentAssigneeId());
            UUID newResolutionCycleId = UUID.randomUUID();

            TicketReopened reopened = Ticket.reopen(
                command.ticketId(), guard.status(), guard.currentAssigneeId(), guard.currentResolutionCycleId(),
                guard.resolutionCycleNumber(), newResolutionCycleId, guard.version(), guard.reopenCount(),
                command.reopenReasonCode(), command.reopenReason(), ownershipStatus,
                command.actor().actorType(), command.actor().subject(), now
            );

            applyUpdate(reopened);

            historyWriter.append(new TicketStatusHistoryEntry(
                UUID.randomUUID(), command.ticketId(), reopened.previousStatus(), reopened.newStatus(),
                reopened.transitionId(), reopened.reasonCode(), command.actor().actorType(), command.actor().subject(),
                command.commandId(), null, null, reopened.aggregateVersion(), now
            ));

            String traceId = currentTraceId();
            auditRecordPort.append(new AuditRecordEntry(
                UUID.randomUUID(), "BUSINESS_ACTION", "TICKET_REOPENED", "ALLOWED",
                command.actor().actorType(), command.actor().subject(), command.actor().clientId(),
                "TICKET", command.ticketId().toString(), guard.displayId().value(),
                reopened.previousStatus().name(), reopened.newStatus().name(),
                traceId, command.commandId(), "SUCCESS", "INTERNAL", now, null, null
            ));
            outboxEventRepository.append(eventMapper.map(reopened, guard.supportQueueId(), traceId, command.correlationId(), command.commandId()));

            ReopenTicketResult result = new ReopenTicketResult(
                command.ticketId(), reopened.previousStatus(), reopened.newStatus(), reopened.previousResolutionCycleId(),
                reopened.newResolutionCycleId(), reopened.reopenReasonCode(), reopened.reopenedById(), reopened.reopenedAt(),
                reopened.reopenCount(), reopened.ownershipStatus(), reopened.aggregateVersion(), false
            );
            completeIdempotency(reservation.idempotencyRecordId(), command.ticketId(), result, now);
            telemetry.recordRequesterReopenCommand("success");
            return result;
        } finally {
            telemetry.stopRequesterReopenTimer(timer);
        }
    }

    private void authorize(ActorContext actor) {
        if (!actor.hasScope(REQUIRED_SCOPE)) {
            telemetry.recordRequesterReopenAuthorizationDenied();
            throw new TicketAuthorizationException(REQUIRED_SCOPE);
        }
    }

    /**
     * domain-rules "Command actor: requester or authorized support actor":
     * an {@code EMPLOYEE} actor must be the ticket's own requester; any
     * other actor type only needed {@link #authorize}'s scope check (it is
     * the "authorized support actor" path — no Support Queue membership is
     * required, unlike {@code ReopenTicketApplicationService}).
     */
    private void checkOwnership(TicketRequesterReopenGuard guard, ActorContext actor) {
        if ("EMPLOYEE".equals(actor.actorType()) && !guard.requesterId().equals(actor.subject())) {
            telemetry.recordRequesterReopenAuthorizationDenied();
            throw new TicketAuthorizationException(REQUIRED_SCOPE);
        }
    }

    private void checkVersion(TicketRequesterReopenGuard guard, long expectedVersion) {
        if (guard.version() != expectedVersion) {
            telemetry.recordRequesterReopenConflict("version");
            throw new TicketVersionConflictException(guard.version());
        }
    }

    private void checkResolutionCycle(TicketRequesterReopenGuard guard) {
        if (guard.currentResolutionCycleId() == null || guard.resolutionCycleStatus() == null) {
            telemetry.recordRequesterReopenConflict("cycle_not_found");
            throw new ResolutionCycleNotFoundException();
        }
    }

    /** SPEC-TW-011 AC-07's reasoning applies identically here: reopen never reassigns. */
    private OwnershipStatus resolveOwnershipStatus(String currentAssigneeId) {
        if (currentAssigneeId == null) {
            return OwnershipStatus.UNASSIGNED;
        }
        Optional<SupportAgentRecord> agent = agentDirectoryPort.findById(currentAssigneeId);
        if (agent.isEmpty() || !agent.get().active()) {
            return OwnershipStatus.ASSIGNEE_INACTIVE;
        }
        return OwnershipStatus.ACTIVE;
    }

    private void applyUpdate(TicketReopened reopened) {
        TicketReopenUpdateOutcome outcome = reopenRepository.applyReopen(new TicketReopenUpdate(
            reopened.ticketId(), reopened.aggregateVersion() - 1, reopened.previousStatus(),
            reopened.previousResolutionCycleId(), reopened.newResolutionCycleNumber() - 1, reopened.newResolutionCycleId(),
            reopened.reopenCount(), reopened.reopenReasonCode(), reopened.reopenReason(), reopened.reopenedByType(),
            reopened.reopenedById(), reopened.reopenedAt(), reopened.occurredAt()
        ));
        if (outcome instanceof TicketReopenUpdateOutcome.TicketMissing) {
            throw new TicketNotFoundException();
        }
        if (outcome instanceof TicketReopenUpdateOutcome.VersionMismatch mismatch) {
            telemetry.recordRequesterReopenConflict("version");
            throw new TicketVersionConflictException(mismatch.currentVersion());
        }
        if (outcome instanceof TicketReopenUpdateOutcome.InvalidState invalidState) {
            telemetry.recordRequesterReopenConflict("state");
            throw new InvalidTicketStateException(invalidState.currentStatus(), Ticket.REOPENABLE_STATUSES);
        }
        if (outcome instanceof TicketReopenUpdateOutcome.ResolutionCycleConflict) {
            telemetry.recordRequesterReopenConflict("cycle_conflict");
            throw new ResolutionCycleNotFoundException();
        }
    }

    private record Reservation(UUID idempotencyRecordId, Instant now, ReopenTicketResult replayed) {
    }

    private Reservation reserveIdempotency(RequesterReopenTicketCommand command) {
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

    private Map<String, Object> canonicalBody(RequesterReopenTicketCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticketId", command.ticketId().toString());
        body.put("reopenReasonCode", command.reopenReasonCode().name());
        body.put("reopenReason", command.reopenReason());
        body.put("expectedVersion", command.expectedVersion());
        return body;
    }

    private void completeIdempotency(UUID idempotencyRecordId, TicketId ticketId, ReopenTicketResult result, Instant now) {
        idempotencyRepository.complete(idempotencyRecordId, new IdempotencyCompletion(
            "TICKET", ticketId.toString(), SUCCESS_HTTP_STATUS, serializeResult(result), now
        ));
    }

    private String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }

    private String serializeResult(ReopenTicketResult result) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ticketId", result.ticketId().value().toString());
            body.put("previousStatus", result.previousStatus().name());
            body.put("status", result.status().name());
            body.put("previousResolutionCycleId", result.previousResolutionCycleId().toString());
            body.put("newResolutionCycleId", result.newResolutionCycleId().toString());
            body.put("reopenReasonCode", result.reopenReasonCode().name());
            body.put("reopenedBy", result.reopenedBy());
            body.put("reopenedAt", result.reopenedAt().toString());
            body.put("reopenCount", result.reopenCount());
            body.put("ownershipStatus", result.ownershipStatus().name());
            body.put("version", result.version());
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize requester reopen ticket result", e);
        }
    }

    private ReopenTicketResult deserializeResult(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.readValue(json, Map.class);
            return new ReopenTicketResult(
                TicketId.of(UUID.fromString((String) body.get("ticketId"))),
                TicketStatus.valueOf((String) body.get("previousStatus")),
                TicketStatus.valueOf((String) body.get("status")),
                UUID.fromString((String) body.get("previousResolutionCycleId")),
                UUID.fromString((String) body.get("newResolutionCycleId")),
                ReopenReasonCode.valueOf((String) body.get("reopenReasonCode")),
                (String) body.get("reopenedBy"),
                Instant.parse((String) body.get("reopenedAt")),
                ((Number) body.get("reopenCount")).intValue(),
                OwnershipStatus.valueOf((String) body.get("ownershipStatus")),
                ((Number) body.get("version")).longValue(),
                true
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored idempotency response", e);
        }
    }
}
