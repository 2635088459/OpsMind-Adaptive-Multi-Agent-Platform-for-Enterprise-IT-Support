package dev.opsmind.ticketworkflow.ticket.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.UpdateTicketAssignmentCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.UpdateTicketAssignmentResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketAssignmentUpdatedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.AssigneeInactiveException;
import dev.opsmind.ticketworkflow.ticket.application.exception.AssigneeNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.exception.AssigneeNotInQueueException;
import dev.opsmind.ticketworkflow.ticket.application.exception.AssigneeNotSupportAgentException;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.RequestInProgressException;
import dev.opsmind.ticketworkflow.ticket.application.exception.SupportQueueInvalidException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketVersionConflictException;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyCompletion;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationOutcome;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationRequest;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.RequestHashCalculator;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.TicketAssignmentHistoryEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.in.UpdateTicketAssignmentUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.CatalogSupportQueue;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.IdempotencyRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SupportAgentDirectoryPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SupportAgentRecord;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SupportQueueCatalogPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SupportQueueMembershipPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAssignmentGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAssignmentGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAssignmentHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAssignmentRouteRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAssignmentRouteUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAssignmentRouteUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketAssignmentUpdated;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidTicketStateException;
import dev.opsmind.ticketworkflow.ticket.domain.model.Ticket;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * SPEC-TW-030 §1/domain-rules: {@code mutable non-terminal state -> same
 * lifecycle state}, updating team/Support Queue/assignee only. Mirrors
 * {@code TicketAssignmentApplicationService}'s (SPEC-TW-008) assignee-
 * eligibility pipeline (agent exists, active, support-capable, and a
 * member of the *target* queue), reusing the same ports and exceptions,
 * but never gates on the actor's own queue membership — domain-rules'
 * "Command actor: support lead, router, or assignment policy" is a scope-
 * only privilege, not a per-queue one like SPEC-TW-008's {@code
 * ticket:assign}.
 */
@Service
public class UpdateTicketAssignmentApplicationService implements UpdateTicketAssignmentUseCase {

    private static final String OPERATION_ID = "updateTicketAssignment";
    private static final String ROUTE_TEMPLATE = "/api/v1/tickets/{ticketId}/assignment";
    private static final String REQUIRED_SCOPE = "ticket:assign-route";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(5);
    private static final int SUCCESS_HTTP_STATUS = 200;
    private static final Set<String> SUPPORT_ROLES = Set.of("IT_SUPPORT", "IT_ADMIN", "IT_MANAGER");

    private final TicketAssignmentGuardPort guardPort;
    private final SupportQueueCatalogPort supportQueueCatalogPort;
    private final SupportAgentDirectoryPort agentDirectoryPort;
    private final SupportQueueMembershipPort queueMembershipPort;
    private final TicketAssignmentRouteRepository repository;
    private final TicketAssignmentHistoryWriter assignmentHistoryWriter;
    private final AuditRecordPort auditRecordPort;
    private final OutboxEventRepository outboxEventRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ClockPort clock;
    private final RequestHashCalculator requestHashCalculator;
    private final TicketAssignmentUpdatedEventMapper eventMapper;
    private final TicketTelemetry telemetry;
    private final ObjectMapper objectMapper;

    public UpdateTicketAssignmentApplicationService(
        TicketAssignmentGuardPort guardPort,
        SupportQueueCatalogPort supportQueueCatalogPort,
        SupportAgentDirectoryPort agentDirectoryPort,
        SupportQueueMembershipPort queueMembershipPort,
        TicketAssignmentRouteRepository repository,
        TicketAssignmentHistoryWriter assignmentHistoryWriter,
        AuditRecordPort auditRecordPort,
        OutboxEventRepository outboxEventRepository,
        IdempotencyRepository idempotencyRepository,
        ClockPort clock,
        RequestHashCalculator requestHashCalculator,
        TicketAssignmentUpdatedEventMapper eventMapper,
        TicketTelemetry telemetry,
        ObjectMapper objectMapper
    ) {
        this.guardPort = guardPort;
        this.supportQueueCatalogPort = supportQueueCatalogPort;
        this.agentDirectoryPort = agentDirectoryPort;
        this.queueMembershipPort = queueMembershipPort;
        this.repository = repository;
        this.assignmentHistoryWriter = assignmentHistoryWriter;
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
    public UpdateTicketAssignmentResult updateAssignment(UpdateTicketAssignmentCommand command) {
        var timer = telemetry.startAssignmentRouteTimer();
        try {
            authorize(command.actor());
            Reservation reservation = reserveIdempotency(command);
            if (reservation.replayed() != null) {
                telemetry.recordAssignmentRouteCommand("replay");
                return reservation.replayed();
            }
            Instant now = reservation.now();

            TicketAssignmentGuard guard = guardPort.loadGuard(command.ticketId()).orElseThrow(TicketNotFoundException::new);
            checkVersion(guard, command.expectedVersion());

            CatalogSupportQueue targetQueue = supportQueueCatalogPort.findActiveById(command.supportQueueId())
                .orElseThrow(() -> {
                    telemetry.recordAssignmentRouteEligibilityDenied("queue_invalid");
                    return new SupportQueueInvalidException();
                });

            SupportAgentRecord agent = command.assigneeId() == null ? null : resolveEligibleAssignee(command.assigneeId(), command.supportQueueId());

            TicketAssignmentUpdated updated = Ticket.updateAssignment(
                command.ticketId(), guard.status(), guard.version(), guard.teamId(), guard.supportQueueId(), guard.currentAssigneeId(),
                targetQueue.teamId(), command.supportQueueId(), command.assigneeId(), command.reason(),
                command.actor().actorType(), command.actor().subject(), now
            );

            applyUpdate(updated);

            assignmentHistoryWriter.append(new TicketAssignmentHistoryEntry(
                UUID.randomUUID(), command.ticketId(), "ROUTED", guard.currentAssigneeId(), command.assigneeId(),
                updated.previousStatus(), updated.newStatus(), command.actor().actorType(), command.actor().subject(),
                command.reason(), now, command.correlationId(), command.commandId(), updated.aggregateVersion()
            ));

            String traceId = currentTraceId();
            auditRecordPort.append(new AuditRecordEntry(
                UUID.randomUUID(), "BUSINESS_ACTION", "TICKET_ASSIGNMENT_UPDATED", "ALLOWED",
                command.actor().actorType(), command.actor().subject(), command.actor().clientId(),
                "TICKET", command.ticketId().toString(), guard.displayId().value(),
                updated.previousStatus().name(), updated.newStatus().name(),
                traceId, command.commandId(), "SUCCESS", "INTERNAL", now, null, null
            ));
            outboxEventRepository.append(eventMapper.map(updated, traceId, command.correlationId(), command.commandId()));

            UpdateTicketAssignmentResult result = new UpdateTicketAssignmentResult(
                command.ticketId(), updated.newStatus(), updated.newTeamId(), command.supportQueueId(), updated.newAssigneeId(),
                agent == null ? null : agent.displayName(), updated.reason(), updated.updatedById(), updated.updatedAt(),
                updated.aggregateVersion(), false
            );
            completeIdempotency(reservation.idempotencyRecordId(), command.ticketId(), result, now);
            telemetry.recordAssignmentRouteCommand("success");
            return result;
        } finally {
            telemetry.stopAssignmentRouteTimer(timer);
        }
    }

    private void authorize(ActorContext actor) {
        if (!actor.hasScope(REQUIRED_SCOPE)) {
            telemetry.recordAssignmentRouteAuthorizationDenied();
            throw new TicketAuthorizationException(REQUIRED_SCOPE);
        }
    }

    private void checkVersion(TicketAssignmentGuard guard, long expectedVersion) {
        if (guard.version() != expectedVersion) {
            telemetry.recordAssignmentRouteConflict("version");
            throw new TicketVersionConflictException(guard.version());
        }
    }

    /** Mirrors {@code TicketAssignmentApplicationService#resolveEligibleAssignee} (SPEC-TW-008) exactly, against the *target* queue. */
    private SupportAgentRecord resolveEligibleAssignee(String assigneeId, SupportQueueId targetSupportQueueId) {
        SupportAgentRecord agent = agentDirectoryPort.findById(assigneeId)
            .orElseThrow(() -> failEligibility("not_found", new AssigneeNotFoundException()));
        if (!agent.active()) {
            throw failEligibility("inactive", new AssigneeInactiveException());
        }
        if (!SUPPORT_ROLES.contains(agent.role())) {
            throw failEligibility("not_support_agent", new AssigneeNotSupportAgentException());
        }
        if (!queueMembershipPort.isMember(assigneeId, targetSupportQueueId)) {
            throw failEligibility("not_in_queue", new AssigneeNotInQueueException());
        }
        return agent;
    }

    private <E extends RuntimeException> E failEligibility(String reason, E exception) {
        telemetry.recordAssignmentRouteEligibilityDenied(reason);
        return exception;
    }

    private void applyUpdate(TicketAssignmentUpdated updated) {
        TicketAssignmentRouteUpdateOutcome outcome = repository.applyRoute(new TicketAssignmentRouteUpdate(
            updated.ticketId(), updated.aggregateVersion() - 1, Ticket.ASSIGNABLE_STATUSES,
            updated.newTeamId(), updated.newSupportQueueId(), updated.newAssigneeId(), updated.occurredAt()
        ));
        if (outcome instanceof TicketAssignmentRouteUpdateOutcome.TicketMissing) {
            throw new TicketNotFoundException();
        }
        if (outcome instanceof TicketAssignmentRouteUpdateOutcome.VersionMismatch mismatch) {
            telemetry.recordAssignmentRouteConflict("version");
            throw new TicketVersionConflictException(mismatch.currentVersion());
        }
        if (outcome instanceof TicketAssignmentRouteUpdateOutcome.InvalidState invalidState) {
            telemetry.recordAssignmentRouteConflict("state");
            throw new InvalidTicketStateException(invalidState.currentStatus(), Ticket.ASSIGNABLE_STATUSES);
        }
    }

    private record Reservation(UUID idempotencyRecordId, Instant now, UpdateTicketAssignmentResult replayed) {
    }

    private Reservation reserveIdempotency(UpdateTicketAssignmentCommand command) {
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

    private Map<String, Object> canonicalBody(UpdateTicketAssignmentCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticketId", command.ticketId().toString());
        body.put("supportQueueId", command.supportQueueId().toString());
        body.put("assigneeId", command.assigneeId());
        body.put("reason", command.reason());
        body.put("expectedVersion", command.expectedVersion());
        return body;
    }

    private void completeIdempotency(UUID idempotencyRecordId, TicketId ticketId, UpdateTicketAssignmentResult result, Instant now) {
        idempotencyRepository.complete(idempotencyRecordId, new IdempotencyCompletion(
            "TICKET", ticketId.toString(), SUCCESS_HTTP_STATUS, serializeResult(result), now
        ));
    }

    private String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }

    private String serializeResult(UpdateTicketAssignmentResult result) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ticketId", result.ticketId().value().toString());
            body.put("status", result.status().name());
            body.put("teamId", result.teamId());
            body.put("supportQueueId", result.supportQueueId().toString());
            body.put("assigneeId", result.assigneeId());
            body.put("assigneeDisplayName", result.assigneeDisplayName());
            body.put("reason", result.reason());
            body.put("updatedBy", result.updatedBy());
            body.put("updatedAt", result.updatedAt().toString());
            body.put("version", result.version());
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize update ticket assignment result", e);
        }
    }

    private UpdateTicketAssignmentResult deserializeResult(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.readValue(json, Map.class);
            return new UpdateTicketAssignmentResult(
                TicketId.of(UUID.fromString((String) body.get("ticketId"))),
                TicketStatus.valueOf((String) body.get("status")),
                (String) body.get("teamId"),
                SupportQueueId.of(UUID.fromString((String) body.get("supportQueueId"))),
                (String) body.get("assigneeId"),
                (String) body.get("assigneeDisplayName"),
                (String) body.get("reason"),
                (String) body.get("updatedBy"),
                Instant.parse((String) body.get("updatedAt")),
                ((Number) body.get("version")).longValue(),
                true
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored idempotency response", e);
        }
    }
}
