package dev.opsmind.ticketworkflow.ticket.application.service;

import dev.opsmind.ticketworkflow.ticket.application.command.ApplyToolExecutionCompletedCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyToolExecutionCompletedResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketToolExecutionCompletedAppliedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.TicketStatusHistoryEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.in.ApplyToolExecutionCompletedUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolExecutionCompletedRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolExecutionCompletedUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolExecutionCompletedUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolExecutionGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolExecutionGuardPort;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketToolExecutionCompletedApplied;
import dev.opsmind.ticketworkflow.ticket.domain.model.Ticket;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * SPEC-TW-019 §1/domain-rules §1: {@code EXECUTING -> VERIFYING} (transitionId
 * {@code SM-021}). Mirrors {@code ApplyApprovalGrantedApplicationService}'s
 * guard-then-write shape (SPEC-TW-015), with one structural difference: the
 * business dedup key here is {@code toolExecutionId} itself (persisted in
 * its own {@code ticket_tool_execution_results} row), not a status column
 * on the row the guard reads — so the idempotency check runs first, against
 * that dedicated table, before the ticket/authorization guard is even
 * loaded. A replayed {@code toolExecutionId} is therefore {@code DUPLICATE}
 * even after the ticket has already moved past {@code EXECUTING} into
 * {@code VERIFYING}, which a guard-only check (as used by Approval Granted)
 * could not tell apart from a genuinely stale event.
 * <p>
 * Classification order matches SPEC-TW-019's own API-contract: an
 * already-recorded {@code toolExecutionId} is {@code DUPLICATE}; no
 * matching {@code GRANTED}/{@code AUTO_APPROVED} authorization for this
 * ticket/workflow/action, a ticket that has moved off {@code EXECUTING}, or
 * an authorization-reference/actionType mismatch is {@code STALE} (an
 * ACK-without-advancing case, not a DLQ case — mirrors Approval Granted's
 * own "wrong action" handling).
 */
@Service
public class ApplyToolExecutionCompletedApplicationService implements ApplyToolExecutionCompletedUseCase {

    private static final Logger log = LoggerFactory.getLogger(ApplyToolExecutionCompletedApplicationService.class);

    private final TicketToolExecutionGuardPort guardPort;
    private final TicketToolExecutionCompletedRepository repository;
    private final TicketHistoryWriter historyWriter;
    private final AuditRecordPort auditRecordPort;
    private final OutboxEventRepository outboxEventRepository;
    private final ClockPort clock;
    private final TicketToolExecutionCompletedAppliedEventMapper eventMapper;
    private final TicketTelemetry telemetry;

    public ApplyToolExecutionCompletedApplicationService(
        TicketToolExecutionGuardPort guardPort,
        TicketToolExecutionCompletedRepository repository,
        TicketHistoryWriter historyWriter,
        AuditRecordPort auditRecordPort,
        OutboxEventRepository outboxEventRepository,
        ClockPort clock,
        TicketToolExecutionCompletedAppliedEventMapper eventMapper,
        TicketTelemetry telemetry
    ) {
        this.guardPort = guardPort;
        this.repository = repository;
        this.historyWriter = historyWriter;
        this.auditRecordPort = auditRecordPort;
        this.outboxEventRepository = outboxEventRepository;
        this.clock = clock;
        this.eventMapper = eventMapper;
        this.telemetry = telemetry;
    }

    @Transactional
    @Override
    public ApplyToolExecutionCompletedResult applyToolExecutionCompleted(ApplyToolExecutionCompletedCommand command) {
        var timer = telemetry.startApplyToolExecutionCompletedTimer();
        try {
            if (repository.existsByToolExecutionId(command.toolExecutionId())) {
                logOutcome("duplicate", "toolExecutionId already recorded", command);
                telemetry.recordApplyToolExecutionCompletedOutcome("duplicate");
                return ApplyToolExecutionCompletedResult.duplicate(command.ticketId(), command.toolExecutionId());
            }

            Optional<TicketToolExecutionGuard> guardOpt = guardPort.loadGuard(command.ticketId(), command.workflowId(), command.actionId());
            if (guardOpt.isEmpty()) {
                logOutcome("stale", "no matching granted/auto-approved authorization", command);
                telemetry.recordApplyToolExecutionCompletedOutcome("stale");
                return ApplyToolExecutionCompletedResult.stale(command.ticketId(), command.toolExecutionId());
            }
            TicketToolExecutionGuard guard = guardOpt.get();

            if (isStale(guard, command)) {
                logOutcome("stale", "ticket no longer EXECUTING or references do not match", command);
                telemetry.recordApplyToolExecutionCompletedOutcome("stale");
                return ApplyToolExecutionCompletedResult.stale(command.ticketId(), command.toolExecutionId());
            }

            Instant now = clock.now();
            TicketToolExecutionCompletedApplied applied = Ticket.applyToolExecutionCompleted(
                command.ticketId(), guard.ticketStatus(), guard.assigneeId(), guard.ticketVersion(),
                command.workflowId(), command.actionId(), guard.authorizationReference(), command.toolExecutionId(),
                command.toolResultId(), command.completedAt(), command.resultSummary(), command.eventId(), now
            );

            TicketToolExecutionCompletedUpdateOutcome outcome = repository.applyToolExecutionCompleted(new TicketToolExecutionCompletedUpdate(
                command.ticketId(), guard.ticketVersion(), command.workflowId(), command.actionId(), guard.authorizationReference(),
                command.toolExecutionId(), command.toolResultId(), command.completedAt(), command.resultSummary(), command.eventId(), now
            ));
            if (outcome instanceof TicketToolExecutionCompletedUpdateOutcome.Conflict) {
                logOutcome("stale", "concurrent modification detected at write time", command);
                telemetry.recordApplyToolExecutionCompletedOutcome("stale");
                return ApplyToolExecutionCompletedResult.stale(command.ticketId(), command.toolExecutionId());
            }

            historyWriter.append(new TicketStatusHistoryEntry(
                UUID.randomUUID(), command.ticketId(), applied.previousStatus(), applied.newStatus(),
                applied.transitionId(), applied.reasonCode(), "SERVICE", "tool-gateway-service",
                null, command.eventId(), command.workflowId(), applied.aggregateVersion(), now
            ));

            String traceId = currentTraceId();
            auditRecordPort.append(new AuditRecordEntry(
                UUID.randomUUID(), "BUSINESS_ACTION", "TOOL_EXECUTION_COMPLETED_APPLIED", "ALLOWED",
                "SERVICE", "tool-gateway-service", "tool-gateway-service",
                "TICKET", command.ticketId().toString(), guard.displayId().value(),
                applied.previousStatus().name(), applied.newStatus().name(),
                traceId, command.eventId(), "SUCCESS", "INTERNAL", now, null, null
            ));
            outboxEventRepository.append(eventMapper.map(applied, guard.supportQueueId(), traceId, command.correlationId(), command.eventId()));

            telemetry.recordApplyToolExecutionCompletedOutcome("applied");
            return ApplyToolExecutionCompletedResult.applied(applied);
        } finally {
            telemetry.stopApplyToolExecutionCompletedTimer(timer);
        }
    }

    private boolean isStale(TicketToolExecutionGuard guard, ApplyToolExecutionCompletedCommand command) {
        if (guard.ticketStatus() != TicketStatus.EXECUTING) {
            return true;
        }
        if (!guard.authorizationReference().equals(command.authorizationReference())) {
            return true;
        }
        return command.actionType() != null && !command.actionType().isBlank() && !guard.actionType().equals(command.actionType());
    }

    private void logOutcome(String outcome, String reason, ApplyToolExecutionCompletedCommand command) {
        log.info(
            "tool.execution.completed event processed as {}: {} (eventId={}, ticketId={}, toolExecutionId={}, workflowId={})",
            outcome, reason, command.eventId(), command.ticketId(), command.toolExecutionId(), command.workflowId()
        );
    }

    private String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }
}
