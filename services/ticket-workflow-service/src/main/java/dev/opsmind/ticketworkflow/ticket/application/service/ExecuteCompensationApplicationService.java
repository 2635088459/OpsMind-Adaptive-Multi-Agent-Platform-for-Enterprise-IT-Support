package dev.opsmind.ticketworkflow.ticket.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.ExecuteCompensationCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ExecuteCompensationResult;
import dev.opsmind.ticketworkflow.ticket.application.event.CompensationExecutedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.CompensationConflictException;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.RequestInProgressException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyCompletion;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationOutcome;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationRequest;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.RequestHashCalculator;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.CompensationRecord;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.in.ExecuteCompensationUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.CompensationAttemptSummary;
import dev.opsmind.ticketworkflow.ticket.application.port.out.CompensationRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.IdempotencyRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketCompensationGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketCompensationGuardPort;
import dev.opsmind.ticketworkflow.ticket.domain.value.CompensationAction;
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
 * SPEC-TW-040 Compensation (Phase 10, {@code
 * /internal/v1/tickets/{ticketId}/compensations}). Mirrors {@code
 * OpenReconciliationCaseApplicationService}'s (SPEC-TW-037) orchestration
 * shape exactly — ticket-scoped, no ticket version/{@code If-Match} check and
 * no ticket-status guard, since domain-rules "cannot run arbitrary SQL or
 * arbitrary state mutation" means this SPEC never mutates the Ticket
 * aggregate, only records which defined {@link CompensationAction} was
 * executed. The whole method stays one transaction, so a rejected attempt
 * (conflict) rolls back cleanly and leaves nothing durable behind except
 * telemetry.
 */
@Service
public class ExecuteCompensationApplicationService implements ExecuteCompensationUseCase {

    private static final String OPERATION_ID = "executeCompensation";
    private static final String ROUTE_TEMPLATE = "/internal/v1/tickets/{ticketId}/compensations";
    private static final String REQUIRED_SCOPE = "ticket:reconciliation:compensate";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(5);
    private static final int SUCCESS_HTTP_STATUS = 200;
    private static final String EVENT_NAME = "ticket.compensation-executed.v1";

    private final TicketCompensationGuardPort guardPort;
    private final CompensationRepository compensationRepository;
    private final AuditRecordPort auditRecordPort;
    private final OutboxEventRepository outboxEventRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ClockPort clock;
    private final RequestHashCalculator requestHashCalculator;
    private final CompensationExecutedEventMapper eventMapper;
    private final TicketTelemetry telemetry;
    private final ObjectMapper objectMapper;

    public ExecuteCompensationApplicationService(
        TicketCompensationGuardPort guardPort,
        CompensationRepository compensationRepository,
        AuditRecordPort auditRecordPort,
        OutboxEventRepository outboxEventRepository,
        IdempotencyRepository idempotencyRepository,
        ClockPort clock,
        RequestHashCalculator requestHashCalculator,
        CompensationExecutedEventMapper eventMapper,
        TicketTelemetry telemetry,
        ObjectMapper objectMapper
    ) {
        this.guardPort = guardPort;
        this.compensationRepository = compensationRepository;
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
    public ExecuteCompensationResult execute(ExecuteCompensationCommand command) {
        var timer = telemetry.startExecuteCompensationTimer();
        try {
            authorize(command.actor());
            Reservation reservation = reserveIdempotency(command);
            if (reservation.replayed() != null) {
                telemetry.recordExecuteCompensationCommand("replay");
                return reservation.replayed();
            }
            Instant now = reservation.now();

            TicketCompensationGuard guard = guardPort.loadGuard(command.ticketId()).orElseThrow(TicketNotFoundException::new);
            CompensationAttemptSummary summary = checkNoOpenAttempt(command);

            CompensationRecord compensationRecord = new CompensationRecord(
                UUID.randomUUID(),
                command.ticketId(),
                command.compensationAction(),
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
            compensationRepository.record(compensationRecord);

            String traceId = currentTraceId();
            auditRecordPort.append(new AuditRecordEntry(
                UUID.randomUUID(), "BUSINESS_ACTION", "COMPENSATION_EXECUTED", "ALLOWED",
                command.actor().actorType(), command.actor().subject(), command.actor().clientId(),
                "TICKET", command.ticketId().toString(), guard.displayId().value(),
                null, null,
                traceId, command.commandId(), "SUCCESS", "INTERNAL", now, null, null
            ));
            outboxEventRepository.append(eventMapper.map(compensationRecord, guard.supportQueueId(), traceId, command.correlationId(), command.commandId()));

            ExecuteCompensationResult result = new ExecuteCompensationResult(
                compensationRecord.id(), ReconciliationDecision.APPLIED, EVENT_NAME, false
            );
            completeIdempotency(reservation.idempotencyRecordId(), command.ticketId(), result, now);
            telemetry.recordExecuteCompensationCommand("success");
            return result;
        } finally {
            telemetry.stopExecuteCompensationTimer(timer);
        }
    }

    private void authorize(ActorContext actor) {
        if (!actor.hasScope(REQUIRED_SCOPE)) {
            telemetry.recordExecuteCompensationAuthorizationDenied();
            throw new TicketAuthorizationException(REQUIRED_SCOPE);
        }
    }

    /**
     * SPEC-TW-040 api-contract §"Errors": {@code 409 CONFLICT} "attempt ...
     * or source reference conflict" — a compensation is already open for
     * this exact {@code (ticketId, sourceReference)} pair, so this attempt
     * (regardless of its {@code idempotencyKey}) cannot proceed until a
     * later recovery phase closes the existing one. Returns the summary so
     * the caller can reuse it for the next {@code attempt_number}.
     */
    private CompensationAttemptSummary checkNoOpenAttempt(ExecuteCompensationCommand command) {
        CompensationAttemptSummary summary = compensationRepository.summarize(command.ticketId(), command.sourceReference());
        if (summary.hasOpenCase()) {
            telemetry.recordExecuteCompensationConflict();
            throw new CompensationConflictException();
        }
        return summary;
    }

    private record Reservation(UUID idempotencyRecordId, Instant now, ExecuteCompensationResult replayed) {
    }

    private Reservation reserveIdempotency(ExecuteCompensationCommand command) {
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

    private Map<String, Object> canonicalBody(ExecuteCompensationCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticketId", command.ticketId().toString());
        body.put("compensationAction", command.compensationAction().name());
        body.put("reasonCode", command.reasonCode().name());
        body.put("reason", command.reason());
        body.put("sourceReference", command.sourceReference());
        return body;
    }

    private void completeIdempotency(UUID idempotencyRecordId, TicketId ticketId, ExecuteCompensationResult result, Instant now) {
        idempotencyRepository.complete(idempotencyRecordId, new IdempotencyCompletion(
            "TICKET", ticketId.toString(), SUCCESS_HTTP_STATUS, serializeResult(result), now
        ));
    }

    private String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }

    private String serializeResult(ExecuteCompensationResult result) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("recoveryId", result.recoveryId().toString());
            body.put("decision", result.decision().name());
            body.put("eventName", result.eventName());
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize execute compensation result", e);
        }
    }

    private ExecuteCompensationResult deserializeResult(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.readValue(json, Map.class);
            return new ExecuteCompensationResult(
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
