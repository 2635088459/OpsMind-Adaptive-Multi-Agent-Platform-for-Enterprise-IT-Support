package dev.opsmind.ticketworkflow.ticket.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.ReplayEventCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ReplayEventResult;
import dev.opsmind.ticketworkflow.ticket.application.event.EventReplayRecordedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.ReplayEventConflictException;
import dev.opsmind.ticketworkflow.ticket.application.exception.ReplaySourceEventNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.exception.RequestInProgressException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyCompletion;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationOutcome;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationRequest;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.RequestHashCalculator;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.ReplayEventRecord;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.in.ReplayEventUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.IdempotencyRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ReplayEventAttemptSummary;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ReplayEventGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ReplayEventGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ReplayEventRepository;
import dev.opsmind.ticketworkflow.ticket.domain.value.ReconciliationDecision;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * SPEC-TW-038 Replay Event (Phase 10, {@code
 * /internal/v1/tickets/events/replay}). Mirrors {@code
 * OpenReconciliationCaseApplicationService}'s (SPEC-TW-037) orchestration
 * shape exactly, with one structural difference: the ticket is not known
 * up front (no {@code ticketId} path variable) — it is resolved from the
 * original event that {@code sourceReference} identifies, so the ticket
 * guard runs first and doubles as "does the source event exist" (api-contract
 * §"Errors" {@code 404}: "target case/event/ticket does not exist"). The
 * whole method stays one transaction: a rejected attempt (source event
 * missing, or a replay already open for it) rolls back cleanly and leaves
 * nothing durable behind except telemetry.
 */
@Service
public class ReplayEventApplicationService implements ReplayEventUseCase {

    private static final String OPERATION_ID = "replayEvent";
    private static final String ROUTE_TEMPLATE = "/internal/v1/tickets/events/replay";
    private static final String REQUIRED_SCOPE = "ticket:reconciliation:replay";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(5);
    private static final int SUCCESS_HTTP_STATUS = 200;
    private static final String EVENT_NAME = "ticket.event-replay-recorded.v1";

    private final ReplayEventGuardPort guardPort;
    private final ReplayEventRepository replayEventRepository;
    private final AuditRecordPort auditRecordPort;
    private final OutboxEventRepository outboxEventRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ClockPort clock;
    private final RequestHashCalculator requestHashCalculator;
    private final EventReplayRecordedEventMapper eventMapper;
    private final TicketTelemetry telemetry;
    private final ObjectMapper objectMapper;

    public ReplayEventApplicationService(
        ReplayEventGuardPort guardPort,
        ReplayEventRepository replayEventRepository,
        AuditRecordPort auditRecordPort,
        OutboxEventRepository outboxEventRepository,
        IdempotencyRepository idempotencyRepository,
        ClockPort clock,
        RequestHashCalculator requestHashCalculator,
        EventReplayRecordedEventMapper eventMapper,
        TicketTelemetry telemetry,
        ObjectMapper objectMapper
    ) {
        this.guardPort = guardPort;
        this.replayEventRepository = replayEventRepository;
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
    public ReplayEventResult replay(ReplayEventCommand command) {
        var timer = telemetry.startReplayEventTimer();
        try {
            authorize(command.actor());
            Reservation reservation = reserveIdempotency(command);
            if (reservation.replayed() != null) {
                telemetry.recordReplayEventCommand("replay");
                return reservation.replayed();
            }
            Instant now = reservation.now();

            ReplayEventGuard guard = guardPort.loadOriginalEvent(command.sourceReference()).orElseThrow(ReplaySourceEventNotFoundException::new);
            ReplayEventAttemptSummary summary = checkNoOpenAttempt(guard.ticketId(), command);

            ReplayEventRecord replayRecord = new ReplayEventRecord(
                UUID.randomUUID(),
                guard.ticketId(),
                command.sourceReference(),
                ReconciliationDecision.APPLIED,
                command.reasonCode(),
                command.reason(),
                command.actor().subject(),
                command.correlationId(),
                command.commandId(),
                summary.totalAttempts() + 1,
                now,
                null
            );
            replayEventRepository.record(replayRecord);

            String traceId = currentTraceId();
            auditRecordPort.append(new AuditRecordEntry(
                UUID.randomUUID(), "BUSINESS_ACTION", "EVENT_REPLAY_RECORDED", "ALLOWED",
                command.actor().actorType(), command.actor().subject(), command.actor().clientId(),
                "TICKET", guard.ticketId().toString(), guard.displayId().value(),
                null, null,
                traceId, command.commandId(), "SUCCESS", "INTERNAL", now, null, null
            ));
            outboxEventRepository.append(eventMapper.map(replayRecord, guard.supportQueueId(), traceId, command.correlationId(), command.commandId()));

            ReplayEventResult result = new ReplayEventResult(
                replayRecord.id(), ReconciliationDecision.APPLIED, EVENT_NAME, false
            );
            completeIdempotency(reservation.idempotencyRecordId(), replayRecord.ticketId(), result, now);
            telemetry.recordReplayEventCommand("success");
            return result;
        } finally {
            telemetry.stopReplayEventTimer(timer);
        }
    }

    private void authorize(ActorContext actor) {
        if (!actor.hasScope(REQUIRED_SCOPE)) {
            telemetry.recordReplayEventAuthorizationDenied();
            throw new TicketAuthorizationException(REQUIRED_SCOPE);
        }
    }

    /**
     * SPEC-TW-038 domain-rules "Replay must be idempotent by both original
     * event id and replay attempt id": a replay already open for this exact
     * {@code (ticketId, sourceReference)} pair blocks a second, concurrent
     * attempt (api-contract §"Errors" {@code 409}). Returns the summary so
     * the caller can reuse it for the next {@code attempt_number}.
     */
    private ReplayEventAttemptSummary checkNoOpenAttempt(TicketId ticketId, ReplayEventCommand command) {
        ReplayEventAttemptSummary summary = replayEventRepository.summarize(ticketId, command.sourceReference());
        if (summary.hasOpenCase()) {
            telemetry.recordReplayEventConflict();
            throw new ReplayEventConflictException();
        }
        return summary;
    }

    private record Reservation(UUID idempotencyRecordId, Instant now, ReplayEventResult replayed) {
    }

    private Reservation reserveIdempotency(ReplayEventCommand command) {
        String actorScope = command.actor().actorType().toLowerCase() + ":" + command.actor().subject() + ":" + command.sourceReference() + ":" + OPERATION_ID;
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

    private Map<String, Object> canonicalBody(ReplayEventCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reasonCode", command.reasonCode().name());
        body.put("reason", command.reason());
        body.put("sourceReference", command.sourceReference());
        return body;
    }

    private void completeIdempotency(UUID idempotencyRecordId, TicketId ticketId, ReplayEventResult result, Instant now) {
        idempotencyRepository.complete(idempotencyRecordId, new IdempotencyCompletion(
            "TICKET", ticketId.toString(), SUCCESS_HTTP_STATUS, serializeResult(result), now
        ));
    }

    private String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }

    private String serializeResult(ReplayEventResult result) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("recoveryId", result.recoveryId().toString());
            body.put("decision", result.decision().name());
            body.put("eventName", result.eventName());
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize replay event result", e);
        }
    }

    private ReplayEventResult deserializeResult(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.readValue(json, Map.class);
            return new ReplayEventResult(
                UUID.fromString((String) body.get("recoveryId")),
                ReconciliationDecision.valueOf((String) body.get("decision")),
                (String) body.get("eventName"),
                true
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored idempotency response", e);
        }
    }
}
