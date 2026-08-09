package dev.opsmind.ticketworkflow.ticket.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.AutoCloseTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.AutoCloseTicketResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketAutoClosedEventMapper;
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
import dev.opsmind.ticketworkflow.ticket.application.port.in.AutoCloseTicketUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.IdempotencyRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAutoCloseGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAutoCloseGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAutoCloseRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAutoCloseUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAutoCloseUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketAutoClosed;
import dev.opsmind.ticketworkflow.ticket.domain.exception.AutoCloseNotYetDueException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidStatusTransitionException;
import dev.opsmind.ticketworkflow.ticket.domain.model.Ticket;
import dev.opsmind.ticketworkflow.ticket.domain.value.CloseReasonCode;
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
 * SPEC-TW-027 §1/domain-rules: {@code RESOLVED -> CLOSED}. Mirrors {@code
 * ConfirmResolutionApplicationService}'s (SPEC-TW-026) orchestration shape,
 * with the authorization step following {@code
 * ResolveTicketWithVerificationApplicationService}'s (SPEC-TW-025) internal-
 * service pattern instead: {@link #authorize} requires the {@code SERVICE}
 * actor type in addition to the scope (domain-rules "Command actor:
 * scheduler policy worker" — no employee/requester path here at all, unlike
 * SPEC-TW-026). {@code Ticket.autoClose(...)} itself re-derives eligibility
 * from the ticket's own {@code auto_close_due_at} rather than trusting
 * whatever the scheduler's request implies (domain-rules: "the scheduler
 * signal is advisory; the service recomputes eligibility under lock").
 */
@Service
public class AutoCloseTicketApplicationService implements AutoCloseTicketUseCase {

    private static final String OPERATION_ID = "autoCloseTicket";
    private static final String ROUTE_TEMPLATE = "/internal/v1/tickets/{ticketId}/auto-close";
    private static final String REQUIRED_SCOPE = "ticket:auto-close";
    private static final String REQUIRED_ACTOR_TYPE = "SERVICE";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(5);
    private static final int SUCCESS_HTTP_STATUS = 200;

    private final TicketAutoCloseGuardPort guardPort;
    private final TicketAutoCloseRepository repository;
    private final TicketHistoryWriter historyWriter;
    private final AuditRecordPort auditRecordPort;
    private final OutboxEventRepository outboxEventRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ClockPort clock;
    private final RequestHashCalculator requestHashCalculator;
    private final TicketAutoClosedEventMapper eventMapper;
    private final TicketTelemetry telemetry;
    private final ObjectMapper objectMapper;

    public AutoCloseTicketApplicationService(
        TicketAutoCloseGuardPort guardPort,
        TicketAutoCloseRepository repository,
        TicketHistoryWriter historyWriter,
        AuditRecordPort auditRecordPort,
        OutboxEventRepository outboxEventRepository,
        IdempotencyRepository idempotencyRepository,
        ClockPort clock,
        RequestHashCalculator requestHashCalculator,
        TicketAutoClosedEventMapper eventMapper,
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
    public AutoCloseTicketResult autoClose(AutoCloseTicketCommand command) {
        var timer = telemetry.startAutoCloseTimer();
        try {
            authorize(command.actor());
            Reservation reservation = reserveIdempotency(command);
            if (reservation.replayed() != null) {
                telemetry.recordAutoCloseCommand("replay");
                return reservation.replayed();
            }
            Instant now = reservation.now();

            TicketAutoCloseGuard guard = guardPort.loadGuard(command.ticketId()).orElseThrow(TicketNotFoundException::new);
            checkVersion(guard, command.expectedVersion());
            checkStatus(guard);
            checkResolutionCycle(guard);
            checkEligibility(guard, now);

            TicketAutoClosed closed = Ticket.autoClose(
                command.ticketId(), guard.status(), guard.currentAssigneeId(), guard.currentResolutionCycleId(),
                guard.version(), guard.autoCloseDueAt(), command.reason(),
                command.actor().actorType(), command.actor().subject(), now
            );

            applyUpdate(closed);

            historyWriter.append(new TicketStatusHistoryEntry(
                UUID.randomUUID(), command.ticketId(), closed.previousStatus(), closed.newStatus(),
                closed.transitionId(), closed.reasonCode(), command.actor().actorType(), command.actor().subject(),
                command.commandId(), null, null, closed.aggregateVersion(), now
            ));

            String traceId = currentTraceId();
            auditRecordPort.append(new AuditRecordEntry(
                UUID.randomUUID(), "BUSINESS_ACTION", "TICKET_AUTO_CLOSED", "ALLOWED",
                command.actor().actorType(), command.actor().subject(), command.actor().clientId(),
                "TICKET", command.ticketId().toString(), guard.displayId().value(),
                closed.previousStatus().name(), closed.newStatus().name(),
                traceId, command.commandId(), "SUCCESS", "INTERNAL", now, null, null
            ));
            outboxEventRepository.append(eventMapper.map(closed, guard.supportQueueId(), traceId, command.correlationId(), command.commandId()));

            AutoCloseTicketResult result = new AutoCloseTicketResult(
                command.ticketId(), closed.previousStatus(), closed.newStatus(), closed.closeReasonCode(),
                closed.closedById(), closed.closedAt(), closed.resolutionCycleId(), closed.aggregateVersion(), false
            );
            completeIdempotency(reservation.idempotencyRecordId(), command.ticketId(), result, now);
            telemetry.recordAutoCloseCommand("success");
            return result;
        } finally {
            telemetry.stopAutoCloseTimer(timer);
        }
    }

    private void authorize(ActorContext actor) {
        if (!REQUIRED_ACTOR_TYPE.equals(actor.actorType()) || !actor.hasScope(REQUIRED_SCOPE)) {
            telemetry.recordAutoCloseAuthorizationDenied();
            throw new TicketAuthorizationException(REQUIRED_SCOPE);
        }
    }

    private void checkVersion(TicketAutoCloseGuard guard, long expectedVersion) {
        if (guard.version() != expectedVersion) {
            telemetry.recordAutoCloseConflict("version");
            throw new TicketVersionConflictException(guard.version());
        }
    }

    private void checkStatus(TicketAutoCloseGuard guard) {
        if (guard.status() != TicketStatus.RESOLVED) {
            telemetry.recordAutoCloseConflict("state");
            throw new InvalidStatusTransitionException(guard.status(), TicketStatus.CLOSED);
        }
    }

    private void checkResolutionCycle(TicketAutoCloseGuard guard) {
        if (guard.currentResolutionCycleId() == null || guard.resolutionCycleStatus() != ResolutionCycleStatus.RESOLVED) {
            telemetry.recordAutoCloseConflict("cycle_not_resolved");
            throw new ResolutionCycleNotFoundException();
        }
    }

    /** domain-rules "the scheduler signal is advisory; the service recomputes eligibility under lock": {@code now} must not be before {@code auto_close_due_at}. */
    private void checkEligibility(TicketAutoCloseGuard guard, Instant now) {
        if (guard.autoCloseDueAt() == null || now.isBefore(guard.autoCloseDueAt())) {
            telemetry.recordAutoCloseConflict("not_yet_due");
            throw new AutoCloseNotYetDueException();
        }
    }

    private void applyUpdate(TicketAutoClosed closed) {
        TicketAutoCloseUpdateOutcome outcome = repository.applyAutoClose(new TicketAutoCloseUpdate(
            closed.ticketId(), closed.aggregateVersion() - 1, closed.resolutionCycleId(), closed.closeReasonCode(),
            closed.reason(), closed.closedByType(), closed.closedById(), closed.closedAt(), closed.occurredAt()
        ));
        if (outcome instanceof TicketAutoCloseUpdateOutcome.TicketMissing) {
            throw new TicketNotFoundException();
        }
        if (outcome instanceof TicketAutoCloseUpdateOutcome.VersionMismatch mismatch) {
            telemetry.recordAutoCloseConflict("version");
            throw new TicketVersionConflictException(mismatch.currentVersion());
        }
        if (outcome instanceof TicketAutoCloseUpdateOutcome.InvalidState invalidState) {
            telemetry.recordAutoCloseConflict("state");
            throw new InvalidStatusTransitionException(invalidState.currentStatus(), closed.newStatus());
        }
        if (outcome instanceof TicketAutoCloseUpdateOutcome.ResolutionCycleConflict) {
            telemetry.recordAutoCloseConflict("cycle_not_resolved");
            throw new ResolutionCycleNotFoundException();
        }
    }

    private record Reservation(UUID idempotencyRecordId, Instant now, AutoCloseTicketResult replayed) {
    }

    private Reservation reserveIdempotency(AutoCloseTicketCommand command) {
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

    private Map<String, Object> canonicalBody(AutoCloseTicketCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticketId", command.ticketId().toString());
        body.put("reason", command.reason());
        body.put("expectedVersion", command.expectedVersion());
        return body;
    }

    private void completeIdempotency(UUID idempotencyRecordId, TicketId ticketId, AutoCloseTicketResult result, Instant now) {
        idempotencyRepository.complete(idempotencyRecordId, new IdempotencyCompletion(
            "TICKET", ticketId.toString(), SUCCESS_HTTP_STATUS, serializeResult(result), now
        ));
    }

    private String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }

    private String serializeResult(AutoCloseTicketResult result) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ticketId", result.ticketId().value().toString());
            body.put("previousStatus", result.previousStatus().name());
            body.put("status", result.status().name());
            body.put("closeReasonCode", result.closeReasonCode().name());
            body.put("closedBy", result.closedBy());
            body.put("closedAt", result.closedAt().toString());
            body.put("resolutionCycleId", result.resolutionCycleId().toString());
            body.put("version", result.version());
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize auto close ticket result", e);
        }
    }

    private AutoCloseTicketResult deserializeResult(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.readValue(json, Map.class);
            return new AutoCloseTicketResult(
                TicketId.of(UUID.fromString((String) body.get("ticketId"))),
                TicketStatus.valueOf((String) body.get("previousStatus")),
                TicketStatus.valueOf((String) body.get("status")),
                CloseReasonCode.valueOf((String) body.get("closeReasonCode")),
                (String) body.get("closedBy"),
                Instant.parse((String) body.get("closedAt")),
                UUID.fromString((String) body.get("resolutionCycleId")),
                ((Number) body.get("version")).longValue(),
                true
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored idempotency response", e);
        }
    }
}
