package dev.opsmind.ticketworkflow.ticket.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.RequestApprovalCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.RequestApprovalResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketApprovalWaitStartedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.ApprovalRequestAlreadyOpenException;
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
import dev.opsmind.ticketworkflow.ticket.application.port.in.RequestApprovalUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.IdempotencyRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketApprovalRequestRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketApprovalRequestUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketApprovalRequestUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketStatusTransitionGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketStatusTransitionGuardPort;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketApprovalWaitStarted;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidStatusTransitionException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.TicketNotAssignedException;
import dev.opsmind.ticketworkflow.ticket.domain.model.Ticket;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApprovalRiskLevel;
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
 * SPEC-TW-014 §1/domain-rules §1-2: {@code IN_PROGRESS -> WAITING_FOR_APPROVAL}
 * with a dedicated open approval-request row. Mirrors {@code
 * RequestUserInputApplicationService}'s orchestration shape (SPEC-TW-012),
 * including the status pre-check ordering lesson from that spec's AC-02: a
 * ticket already {@code WAITING_FOR_APPROVAL} must fail with the specific
 * {@code APPROVAL_REQUEST_ALREADY_OPEN} code, not the generic {@code
 * INVALID_STATUS_TRANSITION}. Reuses {@link TicketStatusTransitionGuardPort}
 * (SPEC-TW-009) rather than a new guard shape, since both commands only need
 * the same ticket/queue/assignee projection.
 */
@Service
public class RequestApprovalApplicationService implements RequestApprovalUseCase {

    private static final String OPERATION_ID = "requestApproval";
    private static final String ROUTE_TEMPLATE = "/api/v1/tickets/{ticketId}/approval-requests";
    private static final String REQUIRED_SCOPE = "ticket:request-approval";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(5);
    private static final int SUCCESS_HTTP_STATUS = 201;

    private final TicketStatusTransitionGuardPort guardPort;
    private final TicketApprovalRequestRepository requestRepository;
    private final TicketHistoryWriter historyWriter;
    private final AuditRecordPort auditRecordPort;
    private final OutboxEventRepository outboxEventRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ClockPort clock;
    private final RequestHashCalculator requestHashCalculator;
    private final TicketApprovalWaitStartedEventMapper eventMapper;
    private final TicketTelemetry telemetry;
    private final ObjectMapper objectMapper;

    public RequestApprovalApplicationService(
        TicketStatusTransitionGuardPort guardPort,
        TicketApprovalRequestRepository requestRepository,
        TicketHistoryWriter historyWriter,
        AuditRecordPort auditRecordPort,
        OutboxEventRepository outboxEventRepository,
        IdempotencyRepository idempotencyRepository,
        ClockPort clock,
        RequestHashCalculator requestHashCalculator,
        TicketApprovalWaitStartedEventMapper eventMapper,
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
    public RequestApprovalResult requestApproval(RequestApprovalCommand command) {
        var timer = telemetry.startRequestApprovalTimer();
        try {
            authorize(command.actor());
            Reservation reservation = reserveIdempotency(command);
            if (reservation.replayed() != null) {
                telemetry.recordRequestApprovalCommand("replay");
                return reservation.replayed();
            }
            Instant now = reservation.now();

            TicketStatusTransitionGuard guard = guardPort.loadGuard(command.ticketId()).orElseThrow(TicketNotFoundException::new);
            checkVersion(guard, command.expectedVersion());
            checkQueueAccess(guard, command.allowedTeamIds());
            checkStatus(guard);

            UUID approvalRequestId = UUID.randomUUID();
            String approvalId = "appr-" + UUID.randomUUID();
            TicketApprovalWaitStarted started = Ticket.requestApproval(
                command.ticketId(), guard.status(), guard.currentAssigneeId(), guard.version(), approvalRequestId, approvalId,
                command.workflowId(), command.actionId(), command.actionType(), command.riskLevel(), command.riskContext(),
                command.reason(), command.actor().actorType(), command.actor().subject(), now
            );

            applyUpdate(started, command);

            historyWriter.append(new TicketStatusHistoryEntry(
                UUID.randomUUID(), command.ticketId(), started.previousStatus(), started.newStatus(),
                started.transitionId(), started.reasonCode(), command.actor().actorType(), command.actor().subject(),
                command.commandId(), null, started.workflowId(), started.aggregateVersion(), now
            ));

            String traceId = currentTraceId();
            auditRecordPort.append(new AuditRecordEntry(
                UUID.randomUUID(), "BUSINESS_ACTION", "APPROVAL_REQUESTED", "ALLOWED",
                command.actor().actorType(), command.actor().subject(), command.actor().clientId(),
                "TICKET", command.ticketId().toString(), guard.displayId().value(),
                started.previousStatus().name(), started.newStatus().name(),
                traceId, command.commandId(), "SUCCESS", "INTERNAL", now, null, null
            ));
            outboxEventRepository.append(eventMapper.map(started, guard.supportQueueId(), traceId, command.correlationId(), command.commandId()));

            RequestApprovalResult result = new RequestApprovalResult(
                command.ticketId(), started.approvalRequestId(), started.approvalId(), started.previousStatus(), started.newStatus(),
                started.workflowId(), started.actionId(), started.actionType(), started.riskLevel(), started.requestedById(),
                started.requestedAt(), started.aggregateVersion(), false
            );
            completeIdempotency(reservation.idempotencyRecordId(), command.ticketId(), result, now);
            telemetry.recordRequestApprovalCommand("success");
            return result;
        } finally {
            telemetry.stopRequestApprovalTimer(timer);
        }
    }

    private void authorize(ActorContext actor) {
        if (!actor.hasScope(REQUIRED_SCOPE)) {
            telemetry.recordRequestApprovalAuthorizationDenied();
            throw new TicketAuthorizationException(REQUIRED_SCOPE);
        }
    }

    private void checkVersion(TicketStatusTransitionGuard guard, long expectedVersion) {
        if (guard.version() != expectedVersion) {
            telemetry.recordRequestApprovalConflict("version");
            throw new TicketVersionConflictException(guard.version());
        }
    }

    private void checkQueueAccess(TicketStatusTransitionGuard guard, Set<String> allowedTeamIds) {
        if (guard.teamId() == null || !allowedTeamIds.contains(guard.teamId())) {
            telemetry.recordRequestApprovalAuthorizationDenied();
            throw new QueueAccessDeniedException();
        }
    }

    /**
     * SPEC-TW-014 acceptance-criteria: a ticket already {@code
     * WAITING_FOR_APPROVAL} means an {@code OPEN} approval request already
     * exists (the invariant this command and SPEC-TW-015/016/017's
     * apply-decision commands jointly maintain) — that specific case must
     * fail with {@code APPROVAL_REQUEST_ALREADY_OPEN}, not the generic
     * {@code INVALID_STATUS_TRANSITION} every other wrong status gets.
     */
    private void checkStatus(TicketStatusTransitionGuard guard) {
        if (guard.status() == TicketStatus.WAITING_FOR_APPROVAL) {
            telemetry.recordRequestApprovalConflict("already_open");
            throw new ApprovalRequestAlreadyOpenException();
        }
        if (guard.status() != TicketStatus.IN_PROGRESS) {
            telemetry.recordRequestApprovalConflict("state");
            throw new InvalidStatusTransitionException(guard.status(), TicketStatus.WAITING_FOR_APPROVAL);
        }
    }

    private void applyUpdate(TicketApprovalWaitStarted started, RequestApprovalCommand command) {
        TicketApprovalRequestUpdateOutcome outcome = requestRepository.applyRequestApproval(new TicketApprovalRequestUpdate(
            started.ticketId(), started.aggregateVersion() - 1, started.approvalRequestId(), started.approvalId(),
            started.workflowId(), started.actionId(), started.actionType(), started.riskLevel().name(), started.riskContext(),
            started.reason(), started.requestedByType(), started.requestedById(), started.requestedAt(),
            command.correlationId(), started.occurredAt()
        ));
        if (outcome instanceof TicketApprovalRequestUpdateOutcome.TicketMissing) {
            throw new TicketNotFoundException();
        }
        if (outcome instanceof TicketApprovalRequestUpdateOutcome.VersionMismatch mismatch) {
            telemetry.recordRequestApprovalConflict("version");
            throw new TicketVersionConflictException(mismatch.currentVersion());
        }
        if (outcome instanceof TicketApprovalRequestUpdateOutcome.NotAssigned) {
            telemetry.recordRequestApprovalConflict("not_assigned");
            throw new TicketNotAssignedException();
        }
        if (outcome instanceof TicketApprovalRequestUpdateOutcome.InvalidState invalidState) {
            telemetry.recordRequestApprovalConflict("state");
            if (invalidState.currentStatus() == TicketStatus.WAITING_FOR_APPROVAL) {
                throw new ApprovalRequestAlreadyOpenException();
            }
            throw new InvalidStatusTransitionException(invalidState.currentStatus(), started.newStatus());
        }
        if (outcome instanceof TicketApprovalRequestUpdateOutcome.RequestAlreadyOpen) {
            telemetry.recordRequestApprovalConflict("already_open");
            throw new ApprovalRequestAlreadyOpenException();
        }
    }

    private record Reservation(UUID idempotencyRecordId, Instant now, RequestApprovalResult replayed) {
    }

    private Reservation reserveIdempotency(RequestApprovalCommand command) {
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

    private Map<String, Object> canonicalBody(RequestApprovalCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticketId", command.ticketId().toString());
        body.put("workflowId", command.workflowId());
        body.put("actionId", command.actionId());
        body.put("actionType", command.actionType());
        body.put("riskLevel", command.riskLevel().name());
        body.put("riskContext", command.riskContext());
        body.put("reason", command.reason());
        body.put("expectedVersion", command.expectedVersion());
        return body;
    }

    private void completeIdempotency(UUID idempotencyRecordId, TicketId ticketId, RequestApprovalResult result, Instant now) {
        idempotencyRepository.complete(idempotencyRecordId, new IdempotencyCompletion(
            "TICKET", ticketId.toString(), SUCCESS_HTTP_STATUS, serializeResult(result), now
        ));
    }

    private String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }

    private String serializeResult(RequestApprovalResult result) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ticketId", result.ticketId().value().toString());
            body.put("approvalRequestId", result.approvalRequestId().toString());
            body.put("approvalId", result.approvalId());
            body.put("previousStatus", result.previousStatus().name());
            body.put("status", result.status().name());
            body.put("workflowId", result.workflowId());
            body.put("actionId", result.actionId());
            body.put("actionType", result.actionType());
            body.put("riskLevel", result.riskLevel().name());
            body.put("requestedBy", result.requestedBy());
            body.put("requestedAt", result.requestedAt().toString());
            body.put("version", result.version());
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize request approval result", e);
        }
    }

    private RequestApprovalResult deserializeResult(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.readValue(json, Map.class);
            return new RequestApprovalResult(
                TicketId.of(UUID.fromString((String) body.get("ticketId"))),
                UUID.fromString((String) body.get("approvalRequestId")),
                (String) body.get("approvalId"),
                TicketStatus.valueOf((String) body.get("previousStatus")),
                TicketStatus.valueOf((String) body.get("status")),
                (String) body.get("workflowId"),
                (String) body.get("actionId"),
                (String) body.get("actionType"),
                ApprovalRiskLevel.valueOf((String) body.get("riskLevel")),
                (String) body.get("requestedBy"),
                Instant.parse((String) body.get("requestedAt")),
                ((Number) body.get("version")).longValue(),
                true
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored idempotency response", e);
        }
    }
}
