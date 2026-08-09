package dev.opsmind.ticketworkflow.ticket.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.CancelTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.CancelTicketResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketCancelledEventMapper;
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
import dev.opsmind.ticketworkflow.ticket.application.port.in.CancelTicketUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.IdempotencyRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketCancelGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketCancelGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketCancelRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketCancelUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketCancelUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketCancelled;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidTicketStateException;
import dev.opsmind.ticketworkflow.ticket.domain.model.Ticket;
import dev.opsmind.ticketworkflow.ticket.domain.value.CancelReasonCode;
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
 * SPEC-TW-029 §1/domain-rules: {@code non-terminal mutable state ->
 * CANCELLED}. Mirrors {@code ConfirmResolutionApplicationService}'s
 * (SPEC-TW-026) authorization shape — domain-rules' "Command actor:
 * requester or authorized support actor" — combined with {@code
 * ReopenTicketApplicationService}'s (SPEC-TW-011) multi-source-status
 * outcome handling ({@code InvalidTicketStateException}, not {@code
 * InvalidStatusTransitionException}), since Cancel, like Reopen, accepts
 * more than one legal source status.
 */
@Service
public class CancelTicketApplicationService implements CancelTicketUseCase {

    private static final String OPERATION_ID = "cancelTicket";
    private static final String ROUTE_TEMPLATE = "/api/v1/tickets/{ticketId}/cancel";
    private static final String REQUIRED_SCOPE = "ticket:cancel";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(5);
    private static final int SUCCESS_HTTP_STATUS = 200;

    private final TicketCancelGuardPort guardPort;
    private final TicketCancelRepository repository;
    private final TicketHistoryWriter historyWriter;
    private final AuditRecordPort auditRecordPort;
    private final OutboxEventRepository outboxEventRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ClockPort clock;
    private final RequestHashCalculator requestHashCalculator;
    private final TicketCancelledEventMapper eventMapper;
    private final TicketTelemetry telemetry;
    private final ObjectMapper objectMapper;
    private final StepUpAuthenticationPolicy stepUpPolicy;
    private final StepUpAuthenticationAuditRecorder stepUpAuditRecorder;

    public CancelTicketApplicationService(
        TicketCancelGuardPort guardPort,
        TicketCancelRepository repository,
        TicketHistoryWriter historyWriter,
        AuditRecordPort auditRecordPort,
        OutboxEventRepository outboxEventRepository,
        IdempotencyRepository idempotencyRepository,
        ClockPort clock,
        RequestHashCalculator requestHashCalculator,
        TicketCancelledEventMapper eventMapper,
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
    public CancelTicketResult cancel(CancelTicketCommand command) {
        var timer = telemetry.startCancelTimer();
        try {
            authorize(command.actor());
            Reservation reservation = reserveIdempotency(command);
            if (reservation.replayed() != null) {
                telemetry.recordCancelCommand("replay");
                return reservation.replayed();
            }
            Instant now = reservation.now();

            TicketCancelGuard guard = guardPort.loadGuard(command.ticketId()).orElseThrow(TicketNotFoundException::new);
            checkOwnership(guard, command.actor());
            checkVersion(guard, command.expectedVersion());

            TicketCancelled cancelled = Ticket.cancel(
                command.ticketId(), guard.status(), guard.currentAssigneeId(), guard.currentResolutionCycleId(),
                guard.version(), command.cancelReasonCode(), command.cancelReason(),
                command.actor().actorType(), command.actor().subject(), now
            );

            requireStepUp(command, now);
            applyUpdate(cancelled);

            historyWriter.append(new TicketStatusHistoryEntry(
                UUID.randomUUID(), command.ticketId(), cancelled.previousStatus(), cancelled.newStatus(),
                cancelled.transitionId(), cancelled.reasonCode(), command.actor().actorType(), command.actor().subject(),
                command.commandId(), null, null, cancelled.aggregateVersion(), now
            ));

            String traceId = currentTraceId();
            auditRecordPort.append(new AuditRecordEntry(
                UUID.randomUUID(), "BUSINESS_ACTION", "TICKET_CANCELLED", "ALLOWED",
                command.actor().actorType(), command.actor().subject(), command.actor().clientId(),
                "TICKET", command.ticketId().toString(), guard.displayId().value(),
                cancelled.previousStatus().name(), cancelled.newStatus().name(),
                traceId, command.commandId(), "SUCCESS", "INTERNAL", now, null, null
            ));
            outboxEventRepository.append(eventMapper.map(cancelled, guard.supportQueueId(), traceId, command.correlationId(), command.commandId()));

            CancelTicketResult result = new CancelTicketResult(
                command.ticketId(), cancelled.previousStatus(), cancelled.newStatus(), cancelled.cancelReasonCode(),
                cancelled.cancelledById(), cancelled.cancelledAt(), cancelled.resolutionCycleId(), cancelled.aggregateVersion(), false
            );
            completeIdempotency(reservation.idempotencyRecordId(), command.ticketId(), result, now);
            telemetry.recordCancelCommand("success");
            return result;
        } finally {
            telemetry.stopCancelTimer(timer);
        }
    }

    private void authorize(ActorContext actor) {
        if (!actor.hasScope(REQUIRED_SCOPE)) {
            telemetry.recordCancelAuthorizationDenied();
            throw new TicketAuthorizationException(REQUIRED_SCOPE);
        }
    }

    /**
     * domain-rules "Command actor: requester or authorized support actor":
     * an {@code EMPLOYEE} actor must be the ticket's own requester; any
     * other actor type only needed {@link #authorize}'s scope check (the
     * "authorized support actor" path — no Support Queue membership is
     * required).
     */
    private void checkOwnership(TicketCancelGuard guard, ActorContext actor) {
        if ("EMPLOYEE".equals(actor.actorType()) && !guard.requesterId().equals(actor.subject())) {
            telemetry.recordCancelAuthorizationDenied();
            throw new TicketAuthorizationException(REQUIRED_SCOPE);
        }
    }

    /**
     * SPEC-TW-036 hardening: Cancel is a phase-09 §4 cross-cutting
     * high-risk command. Runs after domain-level guard/version/state
     * validation (§"Ticket.cancel" above) so a doomed request fails for its
     * own reason first, but strictly before {@link #applyUpdate} — the
     * actual persistence mutation — so a missing/invalid/expired proof
     * never lets the write through (domain-rules: "High-risk commands
     * without valid step-up proof must be rejected before business
     * mutation").
     */
    private void requireStepUp(CancelTicketCommand command, Instant now) {
        String decisionCode = stepUpPolicy.classify(command.stepUpProof(), now);
        if (!StepUpAuthenticationDecisionCode.ALLOWED.equals(decisionCode)) {
            telemetry.recordCancelAuthorizationDenied();
            stepUpAuditRecorder.recordDenied(
                command.ticketId().toString(), command.actor().subject(), command.actor().actorType(), "ticket.cancel",
                decisionCode, command.correlationId(), currentTraceId()
            );
            throw new StepUpAuthenticationRequiredException(decisionCode);
        }
        stepUpAuditRecorder.recordAllowed(
            command.ticketId().toString(), command.actor().subject(), command.actor().actorType(), "ticket.cancel",
            command.correlationId(), currentTraceId()
        );
    }

    private void checkVersion(TicketCancelGuard guard, long expectedVersion) {
        if (guard.version() != expectedVersion) {
            telemetry.recordCancelConflict("version");
            throw new TicketVersionConflictException(guard.version());
        }
    }

    private void applyUpdate(TicketCancelled cancelled) {
        TicketCancelUpdateOutcome outcome = repository.applyCancel(new TicketCancelUpdate(
            cancelled.ticketId(), cancelled.aggregateVersion() - 1, cancelled.previousStatus(), cancelled.resolutionCycleId(),
            cancelled.cancelReasonCode(), cancelled.cancelReason(), cancelled.cancelledByType(), cancelled.cancelledById(),
            cancelled.cancelledAt(), cancelled.occurredAt()
        ));
        if (outcome instanceof TicketCancelUpdateOutcome.TicketMissing) {
            throw new TicketNotFoundException();
        }
        if (outcome instanceof TicketCancelUpdateOutcome.VersionMismatch mismatch) {
            telemetry.recordCancelConflict("version");
            throw new TicketVersionConflictException(mismatch.currentVersion());
        }
        if (outcome instanceof TicketCancelUpdateOutcome.InvalidState invalidState) {
            telemetry.recordCancelConflict("state");
            throw new InvalidTicketStateException(invalidState.currentStatus(), Ticket.CANCELLABLE_STATUSES);
        }
        if (outcome instanceof TicketCancelUpdateOutcome.ResolutionCycleConflict) {
            telemetry.recordCancelConflict("cycle_conflict");
            throw new TicketNotFoundException();
        }
    }

    private record Reservation(UUID idempotencyRecordId, Instant now, CancelTicketResult replayed) {
    }

    private Reservation reserveIdempotency(CancelTicketCommand command) {
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

    private Map<String, Object> canonicalBody(CancelTicketCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticketId", command.ticketId().toString());
        body.put("cancelReasonCode", command.cancelReasonCode().name());
        body.put("cancelReason", command.cancelReason());
        body.put("expectedVersion", command.expectedVersion());
        return body;
    }

    private void completeIdempotency(UUID idempotencyRecordId, TicketId ticketId, CancelTicketResult result, Instant now) {
        idempotencyRepository.complete(idempotencyRecordId, new IdempotencyCompletion(
            "TICKET", ticketId.toString(), SUCCESS_HTTP_STATUS, serializeResult(result), now
        ));
    }

    private String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }

    private String serializeResult(CancelTicketResult result) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ticketId", result.ticketId().value().toString());
            body.put("previousStatus", result.previousStatus().name());
            body.put("status", result.status().name());
            body.put("cancelReasonCode", result.cancelReasonCode().name());
            body.put("cancelledBy", result.cancelledBy());
            body.put("cancelledAt", result.cancelledAt().toString());
            body.put("resolutionCycleId", result.resolutionCycleId().toString());
            body.put("version", result.version());
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize cancel ticket result", e);
        }
    }

    private CancelTicketResult deserializeResult(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.readValue(json, Map.class);
            return new CancelTicketResult(
                TicketId.of(UUID.fromString((String) body.get("ticketId"))),
                TicketStatus.valueOf((String) body.get("previousStatus")),
                TicketStatus.valueOf((String) body.get("status")),
                CancelReasonCode.valueOf((String) body.get("cancelReasonCode")),
                (String) body.get("cancelledBy"),
                Instant.parse((String) body.get("cancelledAt")),
                UUID.fromString((String) body.get("resolutionCycleId")),
                ((Number) body.get("version")).longValue(),
                true
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored idempotency response", e);
        }
    }
}
