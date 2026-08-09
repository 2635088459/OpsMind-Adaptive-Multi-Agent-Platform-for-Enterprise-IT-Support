package dev.opsmind.ticketworkflow.ticket.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.ResumeEscalatedTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ResumeEscalatedTicketResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketEscalationResumedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
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
import dev.opsmind.ticketworkflow.ticket.application.port.in.ResumeEscalatedTicketUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.IdempotencyRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SupportAgentDirectoryPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SupportAgentRecord;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketEscalationResumeGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketEscalationResumeGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketEscalationResumeRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketEscalationResumeUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketEscalationResumeUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketEscalationResumed;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidTicketTransitionException;
import dev.opsmind.ticketworkflow.ticket.domain.model.Ticket;
import dev.opsmind.ticketworkflow.ticket.domain.value.EscalationResumeReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.OwnershipStatus;
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
 * SPEC-TW-032 §1/domain-rules: {@code ESCALATED -> IN_PROGRESS}. Mirrors
 * {@code EscalateTicketApplicationService}'s (SPEC-TW-031) scope-only
 * authorization shape — domain-rules' "Command actor: support lead or
 * escalation owner" is a scope-only privilege, not a requester-ownership
 * one — combined with {@code ReopenTicketApplicationService}'s
 * (SPEC-TW-011) {@code resolveOwnershipStatus} pattern: "Resume must
 * select a next owner/queue" is satisfied by reporting the current
 * owner's standing (never silently reassigning), exactly like Reopen.
 */
@Service
public class ResumeEscalatedTicketApplicationService implements ResumeEscalatedTicketUseCase {

    private static final String OPERATION_ID = "resumeEscalatedTicket";
    private static final String ROUTE_TEMPLATE = "/api/v1/tickets/{ticketId}/escalation/resume";
    private static final String REQUIRED_SCOPE = "ticket:escalation-resume";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(5);
    private static final int SUCCESS_HTTP_STATUS = 200;

    private final TicketEscalationResumeGuardPort guardPort;
    private final SupportAgentDirectoryPort agentDirectoryPort;
    private final TicketEscalationResumeRepository repository;
    private final TicketHistoryWriter historyWriter;
    private final AuditRecordPort auditRecordPort;
    private final OutboxEventRepository outboxEventRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ClockPort clock;
    private final RequestHashCalculator requestHashCalculator;
    private final TicketEscalationResumedEventMapper eventMapper;
    private final TicketTelemetry telemetry;
    private final ObjectMapper objectMapper;

    public ResumeEscalatedTicketApplicationService(
        TicketEscalationResumeGuardPort guardPort,
        SupportAgentDirectoryPort agentDirectoryPort,
        TicketEscalationResumeRepository repository,
        TicketHistoryWriter historyWriter,
        AuditRecordPort auditRecordPort,
        OutboxEventRepository outboxEventRepository,
        IdempotencyRepository idempotencyRepository,
        ClockPort clock,
        RequestHashCalculator requestHashCalculator,
        TicketEscalationResumedEventMapper eventMapper,
        TicketTelemetry telemetry,
        ObjectMapper objectMapper
    ) {
        this.guardPort = guardPort;
        this.agentDirectoryPort = agentDirectoryPort;
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
    public ResumeEscalatedTicketResult resume(ResumeEscalatedTicketCommand command) {
        var timer = telemetry.startEscalationResumeTimer();
        try {
            authorize(command.actor());
            Reservation reservation = reserveIdempotency(command);
            if (reservation.replayed() != null) {
                telemetry.recordEscalationResumeCommand("replay");
                return reservation.replayed();
            }
            Instant now = reservation.now();

            TicketEscalationResumeGuard guard = guardPort.loadGuard(command.ticketId()).orElseThrow(TicketNotFoundException::new);
            checkVersion(guard, command.expectedVersion());

            OwnershipStatus ownershipStatus = resolveOwnershipStatus(guard.currentAssigneeId());

            TicketEscalationResumed resumed = Ticket.resumeEscalation(
                command.ticketId(), guard.status(), guard.version(), guard.teamId(), guard.supportQueueId(),
                guard.currentAssigneeId(), guard.currentResolutionCycleId(),
                command.resumeReasonCode(), command.resumeReason(), ownershipStatus,
                command.actor().actorType(), command.actor().subject(), now
            );

            applyUpdate(resumed);

            historyWriter.append(new TicketStatusHistoryEntry(
                UUID.randomUUID(), command.ticketId(), resumed.previousStatus(), resumed.newStatus(),
                resumed.transitionId(), resumed.reasonCode(), command.actor().actorType(), command.actor().subject(),
                command.commandId(), null, null, resumed.aggregateVersion(), now
            ));

            String traceId = currentTraceId();
            auditRecordPort.append(new AuditRecordEntry(
                UUID.randomUUID(), "BUSINESS_ACTION", "TICKET_ESCALATION_RESUMED", "ALLOWED",
                command.actor().actorType(), command.actor().subject(), command.actor().clientId(),
                "TICKET", command.ticketId().toString(), guard.displayId().value(),
                resumed.previousStatus().name(), resumed.newStatus().name(),
                traceId, command.commandId(), "SUCCESS", "INTERNAL", now, null, null
            ));
            outboxEventRepository.append(eventMapper.map(resumed, traceId, command.correlationId(), command.commandId()));

            ResumeEscalatedTicketResult result = new ResumeEscalatedTicketResult(
                command.ticketId(), resumed.previousStatus(), resumed.newStatus(), resumed.resumeReasonCode(),
                resumed.resumedById(), resumed.resumedAt(), resumed.resolutionCycleId(), resumed.ownershipStatus(),
                resumed.aggregateVersion(), false
            );
            completeIdempotency(reservation.idempotencyRecordId(), command.ticketId(), result, now);
            telemetry.recordEscalationResumeCommand("success");
            return result;
        } finally {
            telemetry.stopEscalationResumeTimer(timer);
        }
    }

    private void authorize(ActorContext actor) {
        if (!actor.hasScope(REQUIRED_SCOPE)) {
            telemetry.recordEscalationResumeAuthorizationDenied();
            throw new TicketAuthorizationException(REQUIRED_SCOPE);
        }
    }

    private void checkVersion(TicketEscalationResumeGuard guard, long expectedVersion) {
        if (guard.version() != expectedVersion) {
            telemetry.recordEscalationResumeConflict("version");
            throw new TicketVersionConflictException(guard.version());
        }
    }

    /** Mirrors {@code ReopenTicketApplicationService#resolveOwnershipStatus} exactly. Informational only; never blocks the resume. */
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

    private void applyUpdate(TicketEscalationResumed resumed) {
        TicketEscalationResumeUpdateOutcome outcome = repository.applyResume(new TicketEscalationResumeUpdate(
            resumed.ticketId(), resumed.aggregateVersion() - 1,
            resumed.resumeReasonCode(), resumed.resumedByType(), resumed.resumedById(), resumed.resumedAt(), resumed.occurredAt()
        ));
        if (outcome instanceof TicketEscalationResumeUpdateOutcome.TicketMissing) {
            throw new TicketNotFoundException();
        }
        if (outcome instanceof TicketEscalationResumeUpdateOutcome.VersionMismatch mismatch) {
            telemetry.recordEscalationResumeConflict("version");
            throw new TicketVersionConflictException(mismatch.currentVersion());
        }
        if (outcome instanceof TicketEscalationResumeUpdateOutcome.InvalidState invalidState) {
            telemetry.recordEscalationResumeConflict("state");
            throw new InvalidTicketTransitionException(invalidState.currentStatus(), TicketStatus.ESCALATED);
        }
    }

    private record Reservation(UUID idempotencyRecordId, Instant now, ResumeEscalatedTicketResult replayed) {
    }

    private Reservation reserveIdempotency(ResumeEscalatedTicketCommand command) {
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

    private Map<String, Object> canonicalBody(ResumeEscalatedTicketCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticketId", command.ticketId().toString());
        body.put("resumeReasonCode", command.resumeReasonCode().name());
        body.put("resumeReason", command.resumeReason());
        body.put("expectedVersion", command.expectedVersion());
        return body;
    }

    private void completeIdempotency(UUID idempotencyRecordId, TicketId ticketId, ResumeEscalatedTicketResult result, Instant now) {
        idempotencyRepository.complete(idempotencyRecordId, new IdempotencyCompletion(
            "TICKET", ticketId.toString(), SUCCESS_HTTP_STATUS, serializeResult(result), now
        ));
    }

    private String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }

    private String serializeResult(ResumeEscalatedTicketResult result) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ticketId", result.ticketId().value().toString());
            body.put("previousStatus", result.previousStatus().name());
            body.put("status", result.status().name());
            body.put("resumeReasonCode", result.resumeReasonCode().name());
            body.put("resumedBy", result.resumedBy());
            body.put("resumedAt", result.resumedAt().toString());
            body.put("resolutionCycleId", result.resolutionCycleId().toString());
            body.put("ownershipStatus", result.ownershipStatus().name());
            body.put("version", result.version());
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize resume escalated ticket result", e);
        }
    }

    private ResumeEscalatedTicketResult deserializeResult(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.readValue(json, Map.class);
            return new ResumeEscalatedTicketResult(
                TicketId.of(UUID.fromString((String) body.get("ticketId"))),
                TicketStatus.valueOf((String) body.get("previousStatus")),
                TicketStatus.valueOf((String) body.get("status")),
                EscalationResumeReasonCode.valueOf((String) body.get("resumeReasonCode")),
                (String) body.get("resumedBy"),
                Instant.parse((String) body.get("resumedAt")),
                UUID.fromString((String) body.get("resolutionCycleId")),
                OwnershipStatus.valueOf((String) body.get("ownershipStatus")),
                ((Number) body.get("version")).longValue(),
                true
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored idempotency response", e);
        }
    }
}
