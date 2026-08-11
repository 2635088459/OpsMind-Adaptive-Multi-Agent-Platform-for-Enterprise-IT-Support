package dev.opsmind.ticketworkflow.ticket.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyDataIntegrityRepairCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyDataIntegrityRepairResult;
import dev.opsmind.ticketworkflow.ticket.application.event.IntegrityRepairAppliedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.DataIntegrityRepairConflictException;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.IntegrityRepairSourceNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.exception.RequestInProgressException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyCompletion;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationOutcome;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationRequest;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.RequestHashCalculator;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.DataIntegrityRepairRecord;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.in.ApplyDataIntegrityRepairUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.DataIntegrityRepairAttemptSummary;
import dev.opsmind.ticketworkflow.ticket.application.port.out.DataIntegrityRepairGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.DataIntegrityRepairGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.DataIntegrityRepairRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.IdempotencyRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
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
 * SPEC-TW-041 Data Integrity Repair (Phase 10, {@code
 * /internal/v1/tickets/integrity-repairs}). Mirrors {@code
 * ReplayEventApplicationService}'s (SPEC-TW-038) orchestration shape
 * exactly: the ticket is not known up front (no {@code ticketId} path
 * variable) — it is resolved from the reconciliation case that {@code
 * sourceReference} identifies (domain-rules: "Repair must first produce a
 * scan finding and repair plan before controlled repair execution" — the
 * finding is the SPEC-TW-037 case this repair binds to), so the guard runs
 * first and doubles as "does the target case exist" (api-contract
 * §"Errors" {@code 404}: "target case/event/ticket does not exist"). The
 * whole method stays one transaction: a rejected attempt (source case
 * missing, or a repair already open for it) rolls back cleanly and leaves
 * nothing durable behind except telemetry.
 */
@Service
public class ApplyDataIntegrityRepairApplicationService implements ApplyDataIntegrityRepairUseCase {

    private static final String OPERATION_ID = "applyDataIntegrityRepair";
    private static final String ROUTE_TEMPLATE = "/internal/v1/tickets/integrity-repairs";
    private static final String REQUIRED_SCOPE = "ticket:reconciliation:repair";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(5);
    private static final int SUCCESS_HTTP_STATUS = 200;
    private static final String EVENT_NAME = "ticket.integrity-repair-applied.v1";

    private final DataIntegrityRepairGuardPort guardPort;
    private final DataIntegrityRepairRepository dataIntegrityRepairRepository;
    private final AuditRecordPort auditRecordPort;
    private final OutboxEventRepository outboxEventRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ClockPort clock;
    private final RequestHashCalculator requestHashCalculator;
    private final IntegrityRepairAppliedEventMapper eventMapper;
    private final TicketTelemetry telemetry;
    private final ObjectMapper objectMapper;

    public ApplyDataIntegrityRepairApplicationService(
        DataIntegrityRepairGuardPort guardPort,
        DataIntegrityRepairRepository dataIntegrityRepairRepository,
        AuditRecordPort auditRecordPort,
        OutboxEventRepository outboxEventRepository,
        IdempotencyRepository idempotencyRepository,
        ClockPort clock,
        RequestHashCalculator requestHashCalculator,
        IntegrityRepairAppliedEventMapper eventMapper,
        TicketTelemetry telemetry,
        ObjectMapper objectMapper
    ) {
        this.guardPort = guardPort;
        this.dataIntegrityRepairRepository = dataIntegrityRepairRepository;
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
    public ApplyDataIntegrityRepairResult apply(ApplyDataIntegrityRepairCommand command) {
        var timer = telemetry.startApplyDataIntegrityRepairTimer();
        try {
            authorize(command.actor());
            Reservation reservation = reserveIdempotency(command);
            if (reservation.replayed() != null) {
                telemetry.recordApplyDataIntegrityRepairCommand("replay");
                return reservation.replayed();
            }
            Instant now = reservation.now();

            DataIntegrityRepairGuard guard = guardPort.loadTargetCase(command.sourceReference()).orElseThrow(IntegrityRepairSourceNotFoundException::new);
            DataIntegrityRepairAttemptSummary summary = checkNoOpenAttempt(guard.ticketId(), command);

            DataIntegrityRepairRecord repairRecord = new DataIntegrityRepairRecord(
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
            dataIntegrityRepairRepository.record(repairRecord);

            String traceId = currentTraceId();
            auditRecordPort.append(new AuditRecordEntry(
                UUID.randomUUID(), "BUSINESS_ACTION", "INTEGRITY_REPAIR_APPLIED", "ALLOWED",
                command.actor().actorType(), command.actor().subject(), command.actor().clientId(),
                "TICKET", guard.ticketId().toString(), guard.displayId().value(),
                null, null,
                traceId, command.commandId(), "SUCCESS", "INTERNAL", now, null, null
            ));
            outboxEventRepository.append(eventMapper.map(repairRecord, guard.supportQueueId(), traceId, command.correlationId(), command.commandId()));

            ApplyDataIntegrityRepairResult result = new ApplyDataIntegrityRepairResult(
                repairRecord.id(), ReconciliationDecision.APPLIED, EVENT_NAME, false
            );
            completeIdempotency(reservation.idempotencyRecordId(), repairRecord.ticketId(), result, now);
            telemetry.recordApplyDataIntegrityRepairCommand("success");
            return result;
        } finally {
            telemetry.stopApplyDataIntegrityRepairTimer(timer);
        }
    }

    private void authorize(ActorContext actor) {
        if (!actor.hasScope(REQUIRED_SCOPE)) {
            telemetry.recordApplyDataIntegrityRepairAuthorizationDenied();
            throw new TicketAuthorizationException(REQUIRED_SCOPE);
        }
    }

    /**
     * SPEC-TW-041 domain-rules "must first produce a scan finding and
     * repair plan before controlled repair execution": a repair already
     * open for this exact {@code (ticketId, sourceReference)} pair blocks a
     * second, concurrent attempt (api-contract §"Errors" {@code 409}).
     * Returns the summary so the caller can reuse it for the next {@code
     * attempt_number}.
     */
    private DataIntegrityRepairAttemptSummary checkNoOpenAttempt(TicketId ticketId, ApplyDataIntegrityRepairCommand command) {
        DataIntegrityRepairAttemptSummary summary = dataIntegrityRepairRepository.summarize(ticketId, command.sourceReference());
        if (summary.hasOpenCase()) {
            telemetry.recordApplyDataIntegrityRepairConflict();
            throw new DataIntegrityRepairConflictException();
        }
        return summary;
    }

    private record Reservation(UUID idempotencyRecordId, Instant now, ApplyDataIntegrityRepairResult replayed) {
    }

    private Reservation reserveIdempotency(ApplyDataIntegrityRepairCommand command) {
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

    private Map<String, Object> canonicalBody(ApplyDataIntegrityRepairCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reasonCode", command.reasonCode().name());
        body.put("reason", command.reason());
        body.put("sourceReference", command.sourceReference());
        return body;
    }

    private void completeIdempotency(UUID idempotencyRecordId, TicketId ticketId, ApplyDataIntegrityRepairResult result, Instant now) {
        idempotencyRepository.complete(idempotencyRecordId, new IdempotencyCompletion(
            "TICKET", ticketId.toString(), SUCCESS_HTTP_STATUS, serializeResult(result), now
        ));
    }

    private String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }

    private String serializeResult(ApplyDataIntegrityRepairResult result) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("recoveryId", result.recoveryId().toString());
            body.put("decision", result.decision().name());
            body.put("eventName", result.eventName());
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize apply data integrity repair result", e);
        }
    }

    private ApplyDataIntegrityRepairResult deserializeResult(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.readValue(json, Map.class);
            return new ApplyDataIntegrityRepairResult(
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
