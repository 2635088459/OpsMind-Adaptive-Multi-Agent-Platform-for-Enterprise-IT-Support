package dev.opsmind.ticketworkflow.ticket.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.PublishCorrectionEventCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.PublishCorrectionEventResult;
import dev.opsmind.ticketworkflow.ticket.application.event.CorrectionEventPublishedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.CorrectionEventConflictException;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.RequestInProgressException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyCompletion;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationOutcome;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationRequest;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.RequestHashCalculator;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.CorrectionEventRecord;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.in.PublishCorrectionEventUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.CorrectionEventAttemptSummary;
import dev.opsmind.ticketworkflow.ticket.application.port.out.CorrectionEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.IdempotencyRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketCorrectionEventGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketCorrectionEventGuardPort;
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
 * SPEC-TW-039 Correction Event (Phase 10, {@code
 * /internal/v1/tickets/{ticketId}/correction-events}). Mirrors {@code
 * OpenReconciliationCaseApplicationService}'s (SPEC-TW-037) orchestration
 * shape exactly — ticket-scoped, no ticket version/{@code If-Match} check
 * and no ticket-status guard, since domain-rules "Correction events must
 * not delete or rewrite original events" means this SPEC never mutates the
 * Ticket aggregate or its history, only publishes an explicit corrective
 * fact bound to {@code sourceReference}. The whole method stays one
 * transaction, so a rejected attempt (conflict) rolls back cleanly and
 * leaves nothing durable behind except telemetry.
 */
@Service
public class PublishCorrectionEventApplicationService implements PublishCorrectionEventUseCase {

    private static final String OPERATION_ID = "publishCorrectionEvent";
    private static final String ROUTE_TEMPLATE = "/internal/v1/tickets/{ticketId}/correction-events";
    private static final String REQUIRED_SCOPE = "ticket:reconciliation:correct";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(5);
    private static final int SUCCESS_HTTP_STATUS = 200;
    private static final String EVENT_NAME = "ticket.correction-event-published.v1";

    private final TicketCorrectionEventGuardPort guardPort;
    private final CorrectionEventRepository correctionEventRepository;
    private final AuditRecordPort auditRecordPort;
    private final OutboxEventRepository outboxEventRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ClockPort clock;
    private final RequestHashCalculator requestHashCalculator;
    private final CorrectionEventPublishedEventMapper eventMapper;
    private final TicketTelemetry telemetry;
    private final ObjectMapper objectMapper;

    public PublishCorrectionEventApplicationService(
        TicketCorrectionEventGuardPort guardPort,
        CorrectionEventRepository correctionEventRepository,
        AuditRecordPort auditRecordPort,
        OutboxEventRepository outboxEventRepository,
        IdempotencyRepository idempotencyRepository,
        ClockPort clock,
        RequestHashCalculator requestHashCalculator,
        CorrectionEventPublishedEventMapper eventMapper,
        TicketTelemetry telemetry,
        ObjectMapper objectMapper
    ) {
        this.guardPort = guardPort;
        this.correctionEventRepository = correctionEventRepository;
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
    public PublishCorrectionEventResult publish(PublishCorrectionEventCommand command) {
        var timer = telemetry.startPublishCorrectionEventTimer();
        try {
            authorize(command.actor());
            Reservation reservation = reserveIdempotency(command);
            if (reservation.replayed() != null) {
                telemetry.recordPublishCorrectionEventCommand("replay");
                return reservation.replayed();
            }
            Instant now = reservation.now();

            TicketCorrectionEventGuard guard = guardPort.loadGuard(command.ticketId()).orElseThrow(TicketNotFoundException::new);
            CorrectionEventAttemptSummary summary = checkNoOpenAttempt(command);

            CorrectionEventRecord correctionRecord = new CorrectionEventRecord(
                UUID.randomUUID(),
                command.ticketId(),
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
            correctionEventRepository.record(correctionRecord);

            String traceId = currentTraceId();
            auditRecordPort.append(new AuditRecordEntry(
                UUID.randomUUID(), "BUSINESS_ACTION", "CORRECTION_EVENT_PUBLISHED", "ALLOWED",
                command.actor().actorType(), command.actor().subject(), command.actor().clientId(),
                "TICKET", command.ticketId().toString(), guard.displayId().value(),
                null, null,
                traceId, command.commandId(), "SUCCESS", "INTERNAL", now, null, null
            ));
            outboxEventRepository.append(eventMapper.map(correctionRecord, guard.supportQueueId(), traceId, command.correlationId(), command.commandId()));

            PublishCorrectionEventResult result = new PublishCorrectionEventResult(
                correctionRecord.id(), ReconciliationDecision.APPLIED, EVENT_NAME, false
            );
            completeIdempotency(reservation.idempotencyRecordId(), command.ticketId(), result, now);
            telemetry.recordPublishCorrectionEventCommand("success");
            return result;
        } finally {
            telemetry.stopPublishCorrectionEventTimer(timer);
        }
    }

    private void authorize(ActorContext actor) {
        if (!actor.hasScope(REQUIRED_SCOPE)) {
            telemetry.recordPublishCorrectionEventAuthorizationDenied();
            throw new TicketAuthorizationException(REQUIRED_SCOPE);
        }
    }

    /**
     * SPEC-TW-039 api-contract §"Errors": {@code 409 CONFLICT} "attempt ...
     * or source reference conflict" — a correction is already open for this
     * exact {@code (ticketId, sourceReference)} pair, so this attempt
     * (regardless of its {@code idempotencyKey}) cannot proceed until a
     * later recovery phase closes the existing one. Returns the summary so
     * the caller can reuse it for the next {@code attempt_number}.
     */
    private CorrectionEventAttemptSummary checkNoOpenAttempt(PublishCorrectionEventCommand command) {
        CorrectionEventAttemptSummary summary = correctionEventRepository.summarize(command.ticketId(), command.sourceReference());
        if (summary.hasOpenCase()) {
            telemetry.recordPublishCorrectionEventConflict();
            throw new CorrectionEventConflictException();
        }
        return summary;
    }

    private record Reservation(UUID idempotencyRecordId, Instant now, PublishCorrectionEventResult replayed) {
    }

    private Reservation reserveIdempotency(PublishCorrectionEventCommand command) {
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

    private Map<String, Object> canonicalBody(PublishCorrectionEventCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticketId", command.ticketId().toString());
        body.put("reasonCode", command.reasonCode().name());
        body.put("reason", command.reason());
        body.put("sourceReference", command.sourceReference());
        return body;
    }

    private void completeIdempotency(UUID idempotencyRecordId, TicketId ticketId, PublishCorrectionEventResult result, Instant now) {
        idempotencyRepository.complete(idempotencyRecordId, new IdempotencyCompletion(
            "TICKET", ticketId.toString(), SUCCESS_HTTP_STATUS, serializeResult(result), now
        ));
    }

    private String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }

    private String serializeResult(PublishCorrectionEventResult result) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("recoveryId", result.recoveryId().toString());
            body.put("decision", result.decision().name());
            body.put("eventName", result.eventName());
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize publish correction event result", e);
        }
    }

    private PublishCorrectionEventResult deserializeResult(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.readValue(json, Map.class);
            return new PublishCorrectionEventResult(
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
