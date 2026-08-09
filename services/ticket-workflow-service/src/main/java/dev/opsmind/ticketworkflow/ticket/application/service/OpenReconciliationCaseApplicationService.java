package dev.opsmind.ticketworkflow.ticket.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.OpenReconciliationCaseCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.OpenReconciliationCaseResult;
import dev.opsmind.ticketworkflow.ticket.application.event.ReconciliationCaseOpenedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.ReconciliationCaseConflictException;
import dev.opsmind.ticketworkflow.ticket.application.exception.RequestInProgressException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyCompletion;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationOutcome;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationRequest;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.RequestHashCalculator;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.ReconciliationCaseRecord;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.in.OpenReconciliationCaseUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.IdempotencyRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ReconciliationCaseAttemptSummary;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ReconciliationCaseRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketReconciliationGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketReconciliationGuardPort;
import dev.opsmind.ticketworkflow.ticket.domain.value.ReconciliationDecision;
import dev.opsmind.ticketworkflow.ticket.domain.value.ReconciliationReasonCode;
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
 * SPEC-TW-037 Open Reconciliation Case (Phase 10, {@code
 * /internal/v1/tickets/{ticketId}/reconciliation-cases}). Mirrors {@code
 * AutoCloseTicketApplicationService}'s (SPEC-TW-027) orchestration shape —
 * authorize, reserve idempotency, load a read-only guard, run the domain
 * guard, write the decision + audit + outbox atomically, complete idempotency
 * — with two deliberate simplifications that follow from domain-rules "must
 * not directly repair business state": no ticket version/{@code If-Match}
 * check (this SPEC never mutates the Ticket aggregate) and no ticket-status
 * guard (a reconciliation case may be opened regardless of the ticket's
 * current lifecycle state). The whole method stays one transaction, so a
 * rejected attempt (conflict) rolls back cleanly and leaves nothing durable
 * behind except the low-cardinality telemetry counters — the same contract
 * every other guarded command in this service already has.
 */
@Service
public class OpenReconciliationCaseApplicationService implements OpenReconciliationCaseUseCase {

    private static final String OPERATION_ID = "openReconciliationCase";
    private static final String ROUTE_TEMPLATE = "/internal/v1/tickets/{ticketId}/reconciliation-cases";
    private static final String REQUIRED_SCOPE = "ticket:reconciliation:open";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(5);
    private static final int SUCCESS_HTTP_STATUS = 200;
    private static final String EVENT_NAME = "ticket.reconciliation-case-opened.v1";

    private final TicketReconciliationGuardPort guardPort;
    private final ReconciliationCaseRepository caseRepository;
    private final AuditRecordPort auditRecordPort;
    private final OutboxEventRepository outboxEventRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ClockPort clock;
    private final RequestHashCalculator requestHashCalculator;
    private final ReconciliationCaseOpenedEventMapper eventMapper;
    private final TicketTelemetry telemetry;
    private final ObjectMapper objectMapper;

    public OpenReconciliationCaseApplicationService(
        TicketReconciliationGuardPort guardPort,
        ReconciliationCaseRepository caseRepository,
        AuditRecordPort auditRecordPort,
        OutboxEventRepository outboxEventRepository,
        IdempotencyRepository idempotencyRepository,
        ClockPort clock,
        RequestHashCalculator requestHashCalculator,
        ReconciliationCaseOpenedEventMapper eventMapper,
        TicketTelemetry telemetry,
        ObjectMapper objectMapper
    ) {
        this.guardPort = guardPort;
        this.caseRepository = caseRepository;
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
    public OpenReconciliationCaseResult openCase(OpenReconciliationCaseCommand command) {
        var timer = telemetry.startOpenReconciliationCaseTimer();
        try {
            authorize(command.actor());
            Reservation reservation = reserveIdempotency(command);
            if (reservation.replayed() != null) {
                telemetry.recordOpenReconciliationCaseCommand("replay");
                return reservation.replayed();
            }
            Instant now = reservation.now();

            TicketReconciliationGuard guard = guardPort.loadGuard(command.ticketId()).orElseThrow(TicketNotFoundException::new);
            ReconciliationCaseAttemptSummary summary = checkNoOpenCase(command);

            ReconciliationCaseRecord caseRecord = new ReconciliationCaseRecord(
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
            caseRepository.record(caseRecord);

            String traceId = currentTraceId();
            auditRecordPort.append(new AuditRecordEntry(
                UUID.randomUUID(), "BUSINESS_ACTION", "RECONCILIATION_CASE_OPENED", "ALLOWED",
                command.actor().actorType(), command.actor().subject(), command.actor().clientId(),
                "TICKET", command.ticketId().toString(), guard.displayId().value(),
                null, null,
                traceId, command.commandId(), "SUCCESS", "INTERNAL", now, null, null
            ));
            outboxEventRepository.append(eventMapper.map(caseRecord, guard.supportQueueId(), traceId, command.correlationId(), command.commandId()));

            OpenReconciliationCaseResult result = new OpenReconciliationCaseResult(
                caseRecord.id(), ReconciliationDecision.APPLIED, EVENT_NAME, false
            );
            completeIdempotency(reservation.idempotencyRecordId(), command.ticketId(), result, now);
            telemetry.recordOpenReconciliationCaseCommand("success");
            return result;
        } finally {
            telemetry.stopOpenReconciliationCaseTimer(timer);
        }
    }

    private void authorize(ActorContext actor) {
        if (!actor.hasScope(REQUIRED_SCOPE)) {
            telemetry.recordOpenReconciliationCaseAuthorizationDenied();
            throw new TicketAuthorizationException(REQUIRED_SCOPE);
        }
    }

    /**
     * SPEC-TW-037 api-contract §"Errors": {@code 409 CONFLICT} "attempt ...
     * or source reference conflict" — a case is already open for this exact
     * {@code (ticketId, sourceReference)} pair, so this attempt (regardless
     * of its {@code idempotencyKey}) cannot proceed until a later recovery
     * phase closes the existing one. Returns the summary so the caller can
     * reuse it for the next {@code attempt_number} without a second query.
     */
    private ReconciliationCaseAttemptSummary checkNoOpenCase(OpenReconciliationCaseCommand command) {
        ReconciliationCaseAttemptSummary summary = caseRepository.summarize(command.ticketId(), command.sourceReference());
        if (summary.hasOpenCase()) {
            telemetry.recordOpenReconciliationCaseConflict();
            throw new ReconciliationCaseConflictException();
        }
        return summary;
    }

    private record Reservation(UUID idempotencyRecordId, Instant now, OpenReconciliationCaseResult replayed) {
    }

    private Reservation reserveIdempotency(OpenReconciliationCaseCommand command) {
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

    private Map<String, Object> canonicalBody(OpenReconciliationCaseCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticketId", command.ticketId().toString());
        body.put("reasonCode", command.reasonCode().name());
        body.put("reason", command.reason());
        body.put("sourceReference", command.sourceReference());
        return body;
    }

    private void completeIdempotency(UUID idempotencyRecordId, TicketId ticketId, OpenReconciliationCaseResult result, Instant now) {
        idempotencyRepository.complete(idempotencyRecordId, new IdempotencyCompletion(
            "TICKET", ticketId.toString(), SUCCESS_HTTP_STATUS, serializeResult(result), now
        ));
    }

    private String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }

    private String serializeResult(OpenReconciliationCaseResult result) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("recoveryId", result.recoveryId().toString());
            body.put("decision", result.decision().name());
            body.put("eventName", result.eventName());
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize open reconciliation case result", e);
        }
    }

    private OpenReconciliationCaseResult deserializeResult(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.readValue(json, Map.class);
            return new OpenReconciliationCaseResult(
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
