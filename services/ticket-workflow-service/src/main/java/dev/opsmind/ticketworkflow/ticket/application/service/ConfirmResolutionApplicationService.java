package dev.opsmind.ticketworkflow.ticket.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.ConfirmResolutionCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ConfirmResolutionResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketResolutionConfirmedEventMapper;
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
import dev.opsmind.ticketworkflow.ticket.application.port.in.ConfirmResolutionUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.IdempotencyRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketResolutionConfirmationGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketResolutionConfirmationGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketResolutionConfirmationRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketResolutionConfirmationUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketResolutionConfirmationUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketResolutionConfirmed;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidStatusTransitionException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.TicketNotAssignedException;
import dev.opsmind.ticketworkflow.ticket.domain.model.Ticket;
import dev.opsmind.ticketworkflow.ticket.domain.value.ResolutionConfirmationReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.ResolutionCycleStatus;
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
 * SPEC-TW-026 §1/domain-rules: {@code RESOLVED -> CLOSED}. Mirrors {@code
 * CloseTicketApplicationService}'s (SPEC-TW-011) orchestration shape almost
 * exactly, with the authorization step being the one deliberate difference:
 * domain-rules' "Command actor: employee or authorized support actor"
 * allows two distinct paths instead of Close's single IT-support-with-
 * queue-membership one — {@link #authorize} requires the {@code
 * REQUIRED_SCOPE} either way, then {@link #checkOwnership} additionally
 * requires an {@code EMPLOYEE} actor to be the ticket's own requester (no
 * Support Queue membership check for either path — domain-rules never
 * mentions one, unlike Close's explicit queue-scoping).
 */
@Service
public class ConfirmResolutionApplicationService implements ConfirmResolutionUseCase {

    private static final String OPERATION_ID = "confirmResolution";
    private static final String ROUTE_TEMPLATE = "/api/v1/tickets/{ticketId}/resolution-confirmation";
    private static final String REQUIRED_SCOPE = "ticket:resolution-confirm";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(5);
    private static final int SUCCESS_HTTP_STATUS = 200;

    private final TicketResolutionConfirmationGuardPort guardPort;
    private final TicketResolutionConfirmationRepository repository;
    private final TicketHistoryWriter historyWriter;
    private final AuditRecordPort auditRecordPort;
    private final OutboxEventRepository outboxEventRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ClockPort clock;
    private final RequestHashCalculator requestHashCalculator;
    private final TicketResolutionConfirmedEventMapper eventMapper;
    private final TicketTelemetry telemetry;
    private final ObjectMapper objectMapper;

    public ConfirmResolutionApplicationService(
        TicketResolutionConfirmationGuardPort guardPort,
        TicketResolutionConfirmationRepository repository,
        TicketHistoryWriter historyWriter,
        AuditRecordPort auditRecordPort,
        OutboxEventRepository outboxEventRepository,
        IdempotencyRepository idempotencyRepository,
        ClockPort clock,
        RequestHashCalculator requestHashCalculator,
        TicketResolutionConfirmedEventMapper eventMapper,
        TicketTelemetry telemetry,
        ObjectMapper objectMapper
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
    }

    @Transactional
    @Override
    public ConfirmResolutionResult confirmResolution(ConfirmResolutionCommand command) {
        var timer = telemetry.startConfirmResolutionTimer();
        try {
            authorize(command.actor());
            Reservation reservation = reserveIdempotency(command);
            if (reservation.replayed() != null) {
                telemetry.recordConfirmResolutionCommand("replay");
                return reservation.replayed();
            }
            Instant now = reservation.now();

            TicketResolutionConfirmationGuard guard = guardPort.loadGuard(command.ticketId()).orElseThrow(TicketNotFoundException::new);
            checkOwnership(guard, command.actor());
            checkVersion(guard, command.expectedVersion());
            checkStatus(guard);
            checkResolutionCycle(guard);

            TicketResolutionConfirmed confirmed = Ticket.confirmResolution(
                command.ticketId(), guard.status(), guard.currentAssigneeId(), guard.currentResolutionCycleId(),
                guard.version(), command.reasonCode(), command.reason(),
                command.actor().actorType(), command.actor().subject(), now
            );

            applyUpdate(confirmed);

            historyWriter.append(new TicketStatusHistoryEntry(
                UUID.randomUUID(), command.ticketId(), confirmed.previousStatus(), confirmed.newStatus(),
                confirmed.transitionId(), confirmed.reasonCode(), command.actor().actorType(), command.actor().subject(),
                command.commandId(), null, null, confirmed.aggregateVersion(), now
            ));

            String traceId = currentTraceId();
            auditRecordPort.append(new AuditRecordEntry(
                UUID.randomUUID(), "BUSINESS_ACTION", "RESOLUTION_CONFIRMED", "ALLOWED",
                command.actor().actorType(), command.actor().subject(), command.actor().clientId(),
                "TICKET", command.ticketId().toString(), guard.displayId().value(),
                confirmed.previousStatus().name(), confirmed.newStatus().name(),
                traceId, command.commandId(), "SUCCESS", "INTERNAL", now, null, null
            ));
            outboxEventRepository.append(eventMapper.map(confirmed, guard.supportQueueId(), traceId, command.correlationId(), command.commandId()));

            ConfirmResolutionResult result = new ConfirmResolutionResult(
                command.ticketId(), confirmed.previousStatus(), confirmed.newStatus(), confirmed.confirmationReasonCode(),
                confirmed.confirmedById(), confirmed.confirmedAt(), confirmed.resolutionCycleId(), confirmed.aggregateVersion(), false
            );
            completeIdempotency(reservation.idempotencyRecordId(), command.ticketId(), result, now);
            telemetry.recordConfirmResolutionCommand("success");
            return result;
        } finally {
            telemetry.stopConfirmResolutionTimer(timer);
        }
    }

    private void authorize(ActorContext actor) {
        if (!actor.hasScope(REQUIRED_SCOPE)) {
            telemetry.recordConfirmResolutionAuthorizationDenied();
            throw new TicketAuthorizationException(REQUIRED_SCOPE);
        }
    }

    /**
     * domain-rules "Command actor: employee or authorized support actor":
     * an {@code EMPLOYEE} actor must be the ticket's own requester; any
     * other actor type only needed {@link #authorize}'s scope check (it is
     * the "authorized support actor" path — no Support Queue membership is
     * required, unlike {@code CloseTicketApplicationService}).
     */
    private void checkOwnership(TicketResolutionConfirmationGuard guard, ActorContext actor) {
        if ("EMPLOYEE".equals(actor.actorType()) && !guard.requesterId().equals(actor.subject())) {
            telemetry.recordConfirmResolutionAuthorizationDenied();
            throw new TicketAuthorizationException(REQUIRED_SCOPE);
        }
    }

    private void checkVersion(TicketResolutionConfirmationGuard guard, long expectedVersion) {
        if (guard.version() != expectedVersion) {
            telemetry.recordConfirmResolutionConflict("version");
            throw new TicketVersionConflictException(guard.version());
        }
    }

    /**
     * SPEC-TW-011 AC-02's reasoning applies identically here: a non-{@code
     * RESOLVED} ticket must fail with {@code INVALID_STATUS_TRANSITION},
     * not the resolution-cycle guard below.
     */
    private void checkStatus(TicketResolutionConfirmationGuard guard) {
        if (guard.status() != TicketStatus.RESOLVED) {
            telemetry.recordConfirmResolutionConflict("state");
            throw new InvalidStatusTransitionException(guard.status(), TicketStatus.CLOSED);
        }
    }

    /** domain-rules "cannot close stale or superseded evidence": only the ticket's *current* cycle, itself already {@code RESOLVED}, may be confirmed. */
    private void checkResolutionCycle(TicketResolutionConfirmationGuard guard) {
        if (guard.currentResolutionCycleId() == null || guard.resolutionCycleStatus() != ResolutionCycleStatus.RESOLVED) {
            telemetry.recordConfirmResolutionConflict("cycle_not_resolved");
            throw new ResolutionCycleNotFoundException();
        }
    }

    private void applyUpdate(TicketResolutionConfirmed confirmed) {
        TicketResolutionConfirmationUpdateOutcome outcome = repository.applyConfirmation(new TicketResolutionConfirmationUpdate(
            confirmed.ticketId(), confirmed.aggregateVersion() - 1, confirmed.resolutionCycleId(), confirmed.confirmationReasonCode(),
            confirmed.reason(), confirmed.confirmedByType(), confirmed.confirmedById(), confirmed.confirmedAt(), confirmed.occurredAt()
        ));
        if (outcome instanceof TicketResolutionConfirmationUpdateOutcome.TicketMissing) {
            throw new TicketNotFoundException();
        }
        if (outcome instanceof TicketResolutionConfirmationUpdateOutcome.VersionMismatch mismatch) {
            telemetry.recordConfirmResolutionConflict("version");
            throw new TicketVersionConflictException(mismatch.currentVersion());
        }
        if (outcome instanceof TicketResolutionConfirmationUpdateOutcome.InvalidState invalidState) {
            telemetry.recordConfirmResolutionConflict("state");
            throw new InvalidStatusTransitionException(invalidState.currentStatus(), confirmed.newStatus());
        }
        if (outcome instanceof TicketResolutionConfirmationUpdateOutcome.ResolutionCycleConflict) {
            telemetry.recordConfirmResolutionConflict("cycle_not_resolved");
            throw new ResolutionCycleNotFoundException();
        }
    }

    private record Reservation(UUID idempotencyRecordId, Instant now, ConfirmResolutionResult replayed) {
    }

    private Reservation reserveIdempotency(ConfirmResolutionCommand command) {
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

    private Map<String, Object> canonicalBody(ConfirmResolutionCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticketId", command.ticketId().toString());
        body.put("reasonCode", command.reasonCode().name());
        body.put("reason", command.reason());
        body.put("expectedVersion", command.expectedVersion());
        return body;
    }

    private void completeIdempotency(UUID idempotencyRecordId, TicketId ticketId, ConfirmResolutionResult result, Instant now) {
        idempotencyRepository.complete(idempotencyRecordId, new IdempotencyCompletion(
            "TICKET", ticketId.toString(), SUCCESS_HTTP_STATUS, serializeResult(result), now
        ));
    }

    private String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }

    private String serializeResult(ConfirmResolutionResult result) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ticketId", result.ticketId().value().toString());
            body.put("previousStatus", result.previousStatus().name());
            body.put("status", result.status().name());
            body.put("reasonCode", result.reasonCode().name());
            body.put("confirmedBy", result.confirmedBy());
            body.put("confirmedAt", result.confirmedAt().toString());
            body.put("resolutionCycleId", result.resolutionCycleId().toString());
            body.put("version", result.version());
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize confirm resolution result", e);
        }
    }

    private ConfirmResolutionResult deserializeResult(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.readValue(json, Map.class);
            return new ConfirmResolutionResult(
                TicketId.of(UUID.fromString((String) body.get("ticketId"))),
                TicketStatus.valueOf((String) body.get("previousStatus")),
                TicketStatus.valueOf((String) body.get("status")),
                ResolutionConfirmationReasonCode.valueOf((String) body.get("reasonCode")),
                (String) body.get("confirmedBy"),
                Instant.parse((String) body.get("confirmedAt")),
                UUID.fromString((String) body.get("resolutionCycleId")),
                ((Number) body.get("version")).longValue(),
                true
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored idempotency response", e);
        }
    }
}
