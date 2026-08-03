package dev.opsmind.ticketworkflow.ticket.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.AssignTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ReassignTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.TicketAssignmentResult;
import dev.opsmind.ticketworkflow.ticket.application.command.UnassignTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketAssignmentEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.AssigneeInactiveException;
import dev.opsmind.ticketworkflow.ticket.application.exception.AssigneeNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.exception.AssigneeNotInQueueException;
import dev.opsmind.ticketworkflow.ticket.application.exception.AssigneeNotSupportAgentException;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.QueueAccessDeniedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.RequestInProgressException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketVersionConflictException;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyCompletion;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationOutcome;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationRequest;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.RequestHashCalculator;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.TicketAssignmentHistoryEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.TicketStatusHistoryEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.in.AssignTicketUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.in.ReassignTicketUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.in.UnassignTicketUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.IdempotencyRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SupportAgentDirectoryPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SupportAgentRecord;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SupportQueueMembershipPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAssignmentGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAssignmentGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAssignmentHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAssignmentRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAssignmentUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAssignmentUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketAssigned;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketReassigned;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketUnassigned;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidTicketStateException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidTicketTransitionException;
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
 * Assign / Reassign / Unassign Ticket (SPEC-TW-008). One service implements
 * all three use cases since they share nearly the entire transactional
 * skeleton (idempotency, guard load, queue authorization, version-guarded
 * update, history/audit/outbox) — only the state guard, assignee handling,
 * and emitted event differ. Mirrors {@code TriageTicketApplicationService}'s
 * shape (SPEC-TW-007) throughout.
 */
@Service
public class TicketAssignmentApplicationService implements AssignTicketUseCase, ReassignTicketUseCase, UnassignTicketUseCase {

    private static final String REQUIRED_SCOPE = "ticket:assign";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(5);
    private static final int SUCCESS_HTTP_STATUS = 200;

    private final TicketAssignmentGuardPort guardPort;
    private final SupportAgentDirectoryPort agentDirectoryPort;
    private final SupportQueueMembershipPort queueMembershipPort;
    private final TicketAssignmentRepository assignmentRepository;
    private final TicketAssignmentHistoryWriter assignmentHistoryWriter;
    private final TicketHistoryWriter statusHistoryWriter;
    private final AuditRecordPort auditRecordPort;
    private final OutboxEventRepository outboxEventRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ClockPort clock;
    private final RequestHashCalculator requestHashCalculator;
    private final TicketAssignmentEventMapper eventMapper;
    private final TicketTelemetry telemetry;
    private final ObjectMapper objectMapper;

    public TicketAssignmentApplicationService(
        TicketAssignmentGuardPort guardPort,
        SupportAgentDirectoryPort agentDirectoryPort,
        SupportQueueMembershipPort queueMembershipPort,
        TicketAssignmentRepository assignmentRepository,
        TicketAssignmentHistoryWriter assignmentHistoryWriter,
        TicketHistoryWriter statusHistoryWriter,
        AuditRecordPort auditRecordPort,
        OutboxEventRepository outboxEventRepository,
        IdempotencyRepository idempotencyRepository,
        ClockPort clock,
        RequestHashCalculator requestHashCalculator,
        TicketAssignmentEventMapper eventMapper,
        TicketTelemetry telemetry,
        ObjectMapper objectMapper
    ) {
        this.guardPort = guardPort;
        this.agentDirectoryPort = agentDirectoryPort;
        this.queueMembershipPort = queueMembershipPort;
        this.assignmentRepository = assignmentRepository;
        this.assignmentHistoryWriter = assignmentHistoryWriter;
        this.statusHistoryWriter = statusHistoryWriter;
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
    public TicketAssignmentResult assign(AssignTicketCommand command) {
        var timer = telemetry.startAssignmentTimer();
        try {
            authorize(command.actor());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ticketId", command.ticketId().toString());
            body.put("assigneeId", command.assigneeId());
            body.put("reason", command.reason());
            body.put("expectedVersion", command.expectedVersion());

            Reservation reservation = reserveIdempotency("assignTicket", command.ticketId(), command.actor(), command.idempotencyKey(), body, "assign");
            if (reservation.replayed() != null) {
                telemetry.recordAssignmentCommand("assign", "replay");
                return reservation.replayed();
            }
            Instant now = reservation.now();

            TicketAssignmentGuard guard = guardPort.loadGuard(command.ticketId()).orElseThrow(TicketNotFoundException::new);
            checkVersion(guard, command.expectedVersion());
            checkQueueAccess(guard, command.allowedTeamIds());

            SupportAgentRecord agent = resolveEligibleAssignee(command.assigneeId(), guard.supportQueueId());

            TicketAssigned assigned = Ticket.assign(
                command.ticketId(), guard.status(), guard.currentAssigneeId(), guard.version(),
                command.assigneeId(), command.actor().actorType(), command.actor().subject(), command.reason(), now
            );

            applyUpdate(command.ticketId(), guard.version(), Set.of(TicketStatus.TRIAGED), TicketStatus.ASSIGNED,
                command.assigneeId(), now, command.actor().subject(), now, TicketStatus.TRIAGED, null);

            assignmentHistoryWriter.append(new TicketAssignmentHistoryEntry(
                UUID.randomUUID(), command.ticketId(), "ASSIGNED", null, command.assigneeId(),
                assigned.previousStatus(), assigned.newStatus(), command.actor().actorType(), command.actor().subject(),
                command.reason(), now, command.correlationId(), command.commandId(), assigned.aggregateVersion()
            ));
            statusHistoryWriter.append(new TicketStatusHistoryEntry(
                UUID.randomUUID(), command.ticketId(), assigned.previousStatus(), assigned.newStatus(),
                "SM-003", "TICKET_ASSIGNED", command.actor().actorType(), command.actor().subject(),
                command.commandId(), null, null, assigned.aggregateVersion(), now
            ));

            String traceId = currentTraceId();
            auditRecordPort.append(auditEntry("TICKET_ASSIGNED", command.actor(), command.ticketId(), guard.displayId().value(),
                assigned.previousStatus(), assigned.newStatus(), traceId, command.commandId(), now));
            outboxEventRepository.append(eventMapper.mapAssigned(assigned, guard.supportQueueId(), traceId, command.correlationId(), command.commandId()));

            TicketAssignmentResult result = new TicketAssignmentResult(
                command.ticketId(), assigned.newStatus(), command.assigneeId(), agent.displayName(), now, assigned.aggregateVersion(), false
            );
            completeIdempotency(reservation.idempotencyRecordId(), command.ticketId(), result, now);
            telemetry.recordAssignmentCommand("assign", "success");
            return result;
        } finally {
            telemetry.stopAssignmentTimer(timer);
        }
    }

    @Transactional
    @Override
    public TicketAssignmentResult reassign(ReassignTicketCommand command) {
        var timer = telemetry.startAssignmentTimer();
        try {
            authorize(command.actor());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ticketId", command.ticketId().toString());
            body.put("assigneeId", command.assigneeId());
            body.put("reason", command.reason());
            body.put("expectedVersion", command.expectedVersion());

            Reservation reservation = reserveIdempotency("reassignTicket", command.ticketId(), command.actor(), command.idempotencyKey(), body, "reassign");
            if (reservation.replayed() != null) {
                telemetry.recordAssignmentCommand("reassign", "replay");
                return reservation.replayed();
            }
            Instant now = reservation.now();

            TicketAssignmentGuard guard = guardPort.loadGuard(command.ticketId()).orElseThrow(TicketNotFoundException::new);
            checkVersion(guard, command.expectedVersion());
            checkQueueAccess(guard, command.allowedTeamIds());

            SupportAgentRecord agent = resolveEligibleAssignee(command.assigneeId(), guard.supportQueueId());

            TicketReassigned reassigned = Ticket.reassign(
                command.ticketId(), guard.status(), guard.currentAssigneeId(), guard.version(),
                command.assigneeId(), command.actor().actorType(), command.actor().subject(), command.reason(), now
            );

            applyUpdate(command.ticketId(), guard.version(), Ticket.REASSIGNABLE_STATUSES, guard.status(),
                command.assigneeId(), now, command.actor().subject(), now, null, Ticket.REASSIGNABLE_STATUSES);

            assignmentHistoryWriter.append(new TicketAssignmentHistoryEntry(
                UUID.randomUUID(), command.ticketId(), "REASSIGNED", guard.currentAssigneeId(), command.assigneeId(),
                reassigned.status(), reassigned.status(), command.actor().actorType(), command.actor().subject(),
                command.reason(), now, command.correlationId(), command.commandId(), reassigned.aggregateVersion()
            ));
            // Reassignment does not add a status-history row: status is unchanged (persistence §3).

            String traceId = currentTraceId();
            auditRecordPort.append(auditEntry("TICKET_REASSIGNED", command.actor(), command.ticketId(), guard.displayId().value(),
                reassigned.status(), reassigned.status(), traceId, command.commandId(), now));
            outboxEventRepository.append(eventMapper.mapReassigned(reassigned, guard.supportQueueId(), traceId, command.correlationId(), command.commandId()));

            TicketAssignmentResult result = new TicketAssignmentResult(
                command.ticketId(), reassigned.status(), command.assigneeId(), agent.displayName(), now, reassigned.aggregateVersion(), false
            );
            completeIdempotency(reservation.idempotencyRecordId(), command.ticketId(), result, now);
            telemetry.recordAssignmentCommand("reassign", "success");
            return result;
        } finally {
            telemetry.stopAssignmentTimer(timer);
        }
    }

    @Transactional
    @Override
    public TicketAssignmentResult unassign(UnassignTicketCommand command) {
        var timer = telemetry.startAssignmentTimer();
        try {
            authorize(command.actor());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ticketId", command.ticketId().toString());
            body.put("reason", command.reason());
            body.put("expectedVersion", command.expectedVersion());

            Reservation reservation = reserveIdempotency("unassignTicket", command.ticketId(), command.actor(), command.idempotencyKey(), body, "unassign");
            if (reservation.replayed() != null) {
                telemetry.recordAssignmentCommand("unassign", "replay");
                return reservation.replayed();
            }
            Instant now = reservation.now();

            TicketAssignmentGuard guard = guardPort.loadGuard(command.ticketId()).orElseThrow(TicketNotFoundException::new);
            checkVersion(guard, command.expectedVersion());
            checkQueueAccess(guard, command.allowedTeamIds());

            TicketUnassigned unassigned = Ticket.unassign(
                command.ticketId(), guard.status(), guard.currentAssigneeId(), guard.version(),
                command.actor().actorType(), command.actor().subject(), command.reason(), now
            );

            applyUpdate(command.ticketId(), guard.version(), Set.of(TicketStatus.ASSIGNED), TicketStatus.TRIAGED,
                null, null, null, now, TicketStatus.ASSIGNED, null);

            assignmentHistoryWriter.append(new TicketAssignmentHistoryEntry(
                UUID.randomUUID(), command.ticketId(), "UNASSIGNED", guard.currentAssigneeId(), null,
                unassigned.previousStatus(), unassigned.newStatus(), command.actor().actorType(), command.actor().subject(),
                command.reason(), now, command.correlationId(), command.commandId(), unassigned.aggregateVersion()
            ));
            statusHistoryWriter.append(new TicketStatusHistoryEntry(
                UUID.randomUUID(), command.ticketId(), unassigned.previousStatus(), unassigned.newStatus(),
                "SM-004", "TICKET_UNASSIGNED", command.actor().actorType(), command.actor().subject(),
                command.commandId(), null, null, unassigned.aggregateVersion(), now
            ));

            String traceId = currentTraceId();
            auditRecordPort.append(auditEntry("TICKET_UNASSIGNED", command.actor(), command.ticketId(), guard.displayId().value(),
                unassigned.previousStatus(), unassigned.newStatus(), traceId, command.commandId(), now));
            outboxEventRepository.append(eventMapper.mapUnassigned(unassigned, guard.supportQueueId(), traceId, command.correlationId(), command.commandId()));

            TicketAssignmentResult result = new TicketAssignmentResult(
                command.ticketId(), unassigned.newStatus(), null, null, null, unassigned.aggregateVersion(), false
            );
            completeIdempotency(reservation.idempotencyRecordId(), command.ticketId(), result, now);
            telemetry.recordAssignmentCommand("unassign", "success");
            return result;
        } finally {
            telemetry.stopAssignmentTimer(timer);
        }
    }

    private void authorize(ActorContext actor) {
        if (!actor.hasScope(REQUIRED_SCOPE)) {
            telemetry.recordAssignmentAuthorizationDenied();
            throw new TicketAuthorizationException(REQUIRED_SCOPE);
        }
    }

    private void checkVersion(TicketAssignmentGuard guard, long expectedVersion) {
        if (guard.version() != expectedVersion) {
            telemetry.recordAssignmentConflict("version");
            throw new TicketVersionConflictException(guard.version());
        }
    }

    private void checkQueueAccess(TicketAssignmentGuard guard, Set<String> allowedTeamIds) {
        if (guard.teamId() == null || !allowedTeamIds.contains(guard.teamId())) {
            telemetry.recordAssignmentAuthorizationDenied();
            throw new QueueAccessDeniedException();
        }
    }

    private SupportAgentRecord resolveEligibleAssignee(String assigneeId, SupportQueueId supportQueueId) {
        SupportAgentRecord agent = agentDirectoryPort.findById(assigneeId)
            .orElseThrow(() -> failEligibility("not_found", new AssigneeNotFoundException()));
        if (!agent.active()) {
            throw failEligibility("inactive", new AssigneeInactiveException());
        }
        if (!SUPPORT_ROLES.contains(agent.role())) {
            throw failEligibility("not_support_agent", new AssigneeNotSupportAgentException());
        }
        if (!queueMembershipPort.isMember(assigneeId, supportQueueId)) {
            throw failEligibility("not_in_queue", new AssigneeNotInQueueException());
        }
        return agent;
    }

    private static final Set<String> SUPPORT_ROLES = Set.of("IT_SUPPORT", "IT_ADMIN", "IT_MANAGER");

    private <E extends RuntimeException> E failEligibility(String reason, E exception) {
        telemetry.recordAssignmentEligibilityDenied(reason);
        return exception;
    }

    /**
     * {@code requiredStatusForAssignOrUnassign} is non-null for Assign/Unassign
     * (single required source status, reported via {@link
     * InvalidTicketTransitionException} on a 0-row reclassification) and
     * null for Reassign, whose {@code allowedStatusesForReassign} is used
     * instead (reported via {@link InvalidTicketStateException}).
     */
    private void applyUpdate(
        TicketId ticketId, long expectedVersion, Set<TicketStatus> requiredCurrentStatuses, TicketStatus newStatus,
        String newAssigneeId, Instant assignedAt, String assignedBy, Instant now,
        TicketStatus requiredStatusForAssignOrUnassign, Set<TicketStatus> allowedStatusesForReassign
    ) {
        TicketAssignmentUpdateOutcome outcome = assignmentRepository.applyAssignment(new TicketAssignmentUpdate(
            ticketId, expectedVersion, requiredCurrentStatuses, newStatus, newAssigneeId, assignedAt, assignedBy, now
        ));

        if (outcome instanceof TicketAssignmentUpdateOutcome.TicketMissing) {
            throw new TicketNotFoundException();
        }
        if (outcome instanceof TicketAssignmentUpdateOutcome.VersionMismatch mismatch) {
            telemetry.recordAssignmentConflict("version");
            throw new TicketVersionConflictException(mismatch.currentVersion());
        }
        if (outcome instanceof TicketAssignmentUpdateOutcome.InvalidState invalidState) {
            telemetry.recordAssignmentConflict("state");
            if (allowedStatusesForReassign != null) {
                throw new InvalidTicketStateException(invalidState.currentStatus(), allowedStatusesForReassign);
            }
            throw new InvalidTicketTransitionException(invalidState.currentStatus(), requiredStatusForAssignOrUnassign);
        }
    }

    private record Reservation(UUID idempotencyRecordId, Instant now, TicketAssignmentResult replayed) {
    }

    private Reservation reserveIdempotency(
        String operationId, TicketId ticketId, ActorContext actor, String idempotencyKey, Map<String, Object> canonicalBody, String routeSuffix
    ) {
        String actorScope = "user:" + actor.subject() + ":" + ticketId + ":" + operationId;
        String requestHash = requestHashCalculator.calculate("POST", "/api/v1/tickets/{ticketId}/" + routeSuffix, actorScope, canonicalBody);

        Instant now = clock.now();
        UUID idempotencyRecordId = UUID.randomUUID();

        IdempotencyReservationOutcome outcome = idempotencyRepository.reserve(new IdempotencyReservationRequest(
            idempotencyRecordId, actorScope, idempotencyKey, operationId, requestHash, now, IDEMPOTENCY_TTL, STALE_THRESHOLD
        ));

        if (outcome instanceof IdempotencyReservationOutcome.Replayed replayed) {
            return new Reservation(idempotencyRecordId, now, deserializeResult(replayed.responseBodyJson()));
        }
        if (outcome instanceof IdempotencyReservationOutcome.KeyReused) {
            throw new IdempotencyKeyReusedException(idempotencyKey);
        }
        if (outcome instanceof IdempotencyReservationOutcome.RequestInProgress) {
            throw new RequestInProgressException(idempotencyKey);
        }
        IdempotencyReservationOutcome.Reserved reserved = (IdempotencyReservationOutcome.Reserved) outcome;
        return new Reservation(reserved.idempotencyRecordId(), now, null);
    }

    private void completeIdempotency(UUID idempotencyRecordId, TicketId ticketId, TicketAssignmentResult result, Instant now) {
        idempotencyRepository.complete(idempotencyRecordId, new IdempotencyCompletion(
            "TICKET", ticketId.toString(), SUCCESS_HTTP_STATUS, serializeResult(result), now
        ));
    }

    private AuditRecordEntry auditEntry(
        String action, ActorContext actor, TicketId ticketId, String displayId,
        TicketStatus statusBefore, TicketStatus statusAfter, String traceId, String commandId, Instant now
    ) {
        return new AuditRecordEntry(
            UUID.randomUUID(), "BUSINESS_ACTION", action, "ALLOWED",
            actor.actorType(), actor.subject(), actor.clientId(),
            "TICKET", ticketId.toString(), displayId,
            statusBefore.name(), statusAfter.name(),
            traceId, commandId, "SUCCESS", "INTERNAL", now, null, null
        );
    }

    private String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }

    private String serializeResult(TicketAssignmentResult result) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ticketId", result.ticketId().value().toString());
            body.put("status", result.status().name());
            body.put("assigneeId", result.assigneeId());
            body.put("assigneeDisplayName", result.assigneeDisplayName());
            body.put("assignedAt", result.assignedAt() == null ? null : result.assignedAt().toString());
            body.put("version", result.version());
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize assignment result", e);
        }
    }

    private TicketAssignmentResult deserializeResult(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.readValue(json, Map.class);
            String assignedAtRaw = (String) body.get("assignedAt");
            return new TicketAssignmentResult(
                TicketId.of(UUID.fromString((String) body.get("ticketId"))),
                TicketStatus.valueOf((String) body.get("status")),
                (String) body.get("assigneeId"),
                (String) body.get("assigneeDisplayName"),
                assignedAtRaw == null ? null : Instant.parse(assignedAtRaw),
                ((Number) body.get("version")).longValue(),
                true
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored idempotency response", e);
        }
    }
}
