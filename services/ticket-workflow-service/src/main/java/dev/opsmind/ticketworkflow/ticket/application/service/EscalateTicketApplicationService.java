package dev.opsmind.ticketworkflow.ticket.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.EscalateTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.EscalateTicketResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketEscalatedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.RequestInProgressException;
import dev.opsmind.ticketworkflow.ticket.application.exception.StepUpAuthenticationRequiredException;
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
import dev.opsmind.ticketworkflow.ticket.application.policy.StepUpAuthenticationAuditRecorder;
import dev.opsmind.ticketworkflow.ticket.application.policy.StepUpAuthenticationDecisionCode;
import dev.opsmind.ticketworkflow.ticket.application.policy.StepUpAuthenticationPolicy;
import dev.opsmind.ticketworkflow.ticket.application.port.in.EscalateTicketUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.IdempotencyRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketEscalationGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketEscalationGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketEscalationRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketEscalationUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketEscalationUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketEscalated;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidTicketStateException;
import dev.opsmind.ticketworkflow.ticket.domain.model.Ticket;
import dev.opsmind.ticketworkflow.ticket.domain.value.EscalationReasonCode;
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
 * SPEC-TW-031 §1/domain-rules: {@code mutable non-terminal state ->
 * ESCALATED}. Mirrors {@code UpdateTicketAssignmentApplicationService}'s
 * (SPEC-TW-030) authorization shape — domain-rules' "Command actor: support
 * actor, policy worker, or failure handler" is a scope-only privilege, not
 * a requester-ownership one like Cancel/Confirm-Resolution/Requester-
 * Reopen — combined with {@code CancelTicketApplicationService}'s
 * (SPEC-TW-029) multi-source-status outcome handling ({@code
 * InvalidTicketStateException}), since Escalate, like Cancel, accepts more
 * than one legal source status.
 */
@Service
public class EscalateTicketApplicationService implements EscalateTicketUseCase {

    private static final String OPERATION_ID = "escalateTicket";
    private static final String ROUTE_TEMPLATE = "/api/v1/tickets/{ticketId}/escalation";
    private static final String REQUIRED_SCOPE = "ticket:escalate";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(5);
    private static final int SUCCESS_HTTP_STATUS = 200;

    private final TicketEscalationGuardPort guardPort;
    private final TicketEscalationRepository repository;
    private final TicketHistoryWriter historyWriter;
    private final AuditRecordPort auditRecordPort;
    private final OutboxEventRepository outboxEventRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ClockPort clock;
    private final RequestHashCalculator requestHashCalculator;
    private final TicketEscalatedEventMapper eventMapper;
    private final TicketTelemetry telemetry;
    private final ObjectMapper objectMapper;
    private final StepUpAuthenticationPolicy stepUpPolicy;
    private final StepUpAuthenticationAuditRecorder stepUpAuditRecorder;

    public EscalateTicketApplicationService(
        TicketEscalationGuardPort guardPort,
        TicketEscalationRepository repository,
        TicketHistoryWriter historyWriter,
        AuditRecordPort auditRecordPort,
        OutboxEventRepository outboxEventRepository,
        IdempotencyRepository idempotencyRepository,
        ClockPort clock,
        RequestHashCalculator requestHashCalculator,
        TicketEscalatedEventMapper eventMapper,
        TicketTelemetry telemetry,
        ObjectMapper objectMapper,
        StepUpAuthenticationPolicy stepUpPolicy,
        StepUpAuthenticationAuditRecorder stepUpAuditRecorder
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
        this.stepUpPolicy = stepUpPolicy;
        this.stepUpAuditRecorder = stepUpAuditRecorder;
    }

    @Transactional
    @Override
    public EscalateTicketResult escalate(EscalateTicketCommand command) {
        var timer = telemetry.startEscalateTimer();
        try {
            authorize(command.actor());
            Reservation reservation = reserveIdempotency(command);
            if (reservation.replayed() != null) {
                telemetry.recordEscalateCommand("replay");
                return reservation.replayed();
            }
            Instant now = reservation.now();

            TicketEscalationGuard guard = guardPort.loadGuard(command.ticketId()).orElseThrow(TicketNotFoundException::new);
            checkVersion(guard, command.expectedVersion());

            TicketEscalated escalated = Ticket.escalate(
                command.ticketId(), guard.status(), guard.version(), guard.teamId(), guard.supportQueueId(),
                guard.currentAssigneeId(), guard.currentResolutionCycleId(), guard.activeWorkflowId(),
                command.escalationReasonCode(), command.escalationReason(),
                command.actor().actorType(), command.actor().subject(), now
            );

            requireStepUp(command, now);
            applyUpdate(escalated);

            historyWriter.append(new TicketStatusHistoryEntry(
                UUID.randomUUID(), command.ticketId(), escalated.previousStatus(), escalated.newStatus(),
                escalated.transitionId(), escalated.reasonCode(), command.actor().actorType(), command.actor().subject(),
                command.commandId(), null, escalated.workflowId(), escalated.aggregateVersion(), now
            ));

            String traceId = currentTraceId();
            auditRecordPort.append(new AuditRecordEntry(
                UUID.randomUUID(), "BUSINESS_ACTION", "TICKET_ESCALATED", "ALLOWED",
                command.actor().actorType(), command.actor().subject(), command.actor().clientId(),
                "TICKET", command.ticketId().toString(), guard.displayId().value(),
                escalated.previousStatus().name(), escalated.newStatus().name(),
                traceId, command.commandId(), "SUCCESS", "INTERNAL", now, null, null
            ));
            outboxEventRepository.append(eventMapper.map(escalated, traceId, command.correlationId(), command.commandId()));

            EscalateTicketResult result = new EscalateTicketResult(
                command.ticketId(), escalated.previousStatus(), escalated.newStatus(), escalated.escalationReasonCode(),
                escalated.escalatedById(), escalated.escalatedAt(), escalated.resolutionCycleId(), escalated.aggregateVersion(), false
            );
            completeIdempotency(reservation.idempotencyRecordId(), command.ticketId(), result, now);
            telemetry.recordEscalateCommand("success");
            return result;
        } finally {
            telemetry.stopEscalateTimer(timer);
        }
    }

    private void authorize(ActorContext actor) {
        if (!actor.hasScope(REQUIRED_SCOPE)) {
            telemetry.recordEscalateAuthorizationDenied();
            throw new TicketAuthorizationException(REQUIRED_SCOPE);
        }
    }

    /**
     * SPEC-TW-036 hardening: Escalate is a phase-09 §4 cross-cutting
     * high-risk command. Runs after domain-level guard/version/state
     * validation ({@code Ticket.escalate} above) so a doomed request fails
     * for its own reason first, but strictly before {@link #applyUpdate} —
     * the actual persistence mutation — so a missing/invalid/expired proof
     * never lets the write through (domain-rules: "High-risk commands
     * without valid step-up proof must be rejected before business
     * mutation").
     */
    private void requireStepUp(EscalateTicketCommand command, Instant now) {
        String decisionCode = stepUpPolicy.classify(command.stepUpProof(), now);
        if (!StepUpAuthenticationDecisionCode.ALLOWED.equals(decisionCode)) {
            telemetry.recordEscalateAuthorizationDenied();
            stepUpAuditRecorder.recordDenied(
                command.ticketId().toString(), command.actor().subject(), command.actor().actorType(), "ticket.escalate",
                decisionCode, command.correlationId(), currentTraceId()
            );
            throw new StepUpAuthenticationRequiredException(decisionCode);
        }
        stepUpAuditRecorder.recordAllowed(
            command.ticketId().toString(), command.actor().subject(), command.actor().actorType(), "ticket.escalate",
            command.correlationId(), currentTraceId()
        );
    }

    private void checkVersion(TicketEscalationGuard guard, long expectedVersion) {
        if (guard.version() != expectedVersion) {
            telemetry.recordEscalateConflict("version");
            throw new TicketVersionConflictException(guard.version());
        }
    }

    private void applyUpdate(TicketEscalated escalated) {
        TicketEscalationUpdateOutcome outcome = repository.applyEscalation(new TicketEscalationUpdate(
            escalated.ticketId(), escalated.aggregateVersion() - 1, escalated.previousStatus(),
            escalated.escalationReasonCode(), escalated.escalatedByType(), escalated.escalatedById(),
            escalated.escalatedAt(), escalated.occurredAt()
        ));
        if (outcome instanceof TicketEscalationUpdateOutcome.TicketMissing) {
            throw new TicketNotFoundException();
        }
        if (outcome instanceof TicketEscalationUpdateOutcome.VersionMismatch mismatch) {
            telemetry.recordEscalateConflict("version");
            throw new TicketVersionConflictException(mismatch.currentVersion());
        }
        if (outcome instanceof TicketEscalationUpdateOutcome.InvalidState invalidState) {
            telemetry.recordEscalateConflict("state");
            throw new InvalidTicketStateException(invalidState.currentStatus(), Ticket.ESCALATABLE_STATUSES);
        }
    }

    private record Reservation(UUID idempotencyRecordId, Instant now, EscalateTicketResult replayed) {
    }

    private Reservation reserveIdempotency(EscalateTicketCommand command) {
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

    private Map<String, Object> canonicalBody(EscalateTicketCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticketId", command.ticketId().toString());
        body.put("escalationReasonCode", command.escalationReasonCode().name());
        body.put("escalationReason", command.escalationReason());
        body.put("expectedVersion", command.expectedVersion());
        return body;
    }

    private void completeIdempotency(UUID idempotencyRecordId, TicketId ticketId, EscalateTicketResult result, Instant now) {
        idempotencyRepository.complete(idempotencyRecordId, new IdempotencyCompletion(
            "TICKET", ticketId.toString(), SUCCESS_HTTP_STATUS, serializeResult(result), now
        ));
    }

    private String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }

    private String serializeResult(EscalateTicketResult result) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ticketId", result.ticketId().value().toString());
            body.put("previousStatus", result.previousStatus().name());
            body.put("status", result.status().name());
            body.put("escalationReasonCode", result.escalationReasonCode().name());
            body.put("escalatedBy", result.escalatedBy());
            body.put("escalatedAt", result.escalatedAt().toString());
            body.put("resolutionCycleId", result.resolutionCycleId().toString());
            body.put("version", result.version());
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize escalate ticket result", e);
        }
    }

    private EscalateTicketResult deserializeResult(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.readValue(json, Map.class);
            return new EscalateTicketResult(
                TicketId.of(UUID.fromString((String) body.get("ticketId"))),
                TicketStatus.valueOf((String) body.get("previousStatus")),
                TicketStatus.valueOf((String) body.get("status")),
                EscalationReasonCode.valueOf((String) body.get("escalationReasonCode")),
                (String) body.get("escalatedBy"),
                Instant.parse((String) body.get("escalatedAt")),
                UUID.fromString((String) body.get("resolutionCycleId")),
                ((Number) body.get("version")).longValue(),
                true
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored idempotency response", e);
        }
    }
}
