package dev.opsmind.ticketworkflow.ticket.application.service;

import dev.opsmind.ticketworkflow.ticket.application.command.ApplyToolExecutionFailedCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyToolExecutionFailedResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketToolExecutionFailedAppliedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.TicketStatusHistoryEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.in.ApplyToolExecutionFailedUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolExecutionFailedRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolExecutionFailedUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolExecutionFailedUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolExecutionFailureGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolExecutionGuard;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketToolExecutionFailedApplied;
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
 * SPEC-TW-020 §1/domain-rules §1: {@code EXECUTING -> IN_PROGRESS} (known-safe
 * failure, transitionId {@code SM-022}) or {@code EXECUTING -> FAILED}
 * (pipeline failure, transitionId {@code SM-023}). Mirrors {@code
 * ApplyToolExecutionCompletedApplicationService}'s guard-then-write shape
 * (SPEC-TW-019) exactly, including running the {@code toolExecutionId}
 * idempotency check before the ticket/authorization guard so a replayed
 * {@code toolExecutionId} is {@code DUPLICATE} even after the ticket has
 * already moved off {@code EXECUTING}.
 */
@Service
public class ApplyToolExecutionFailedApplicationService implements ApplyToolExecutionFailedUseCase {

    private static final Logger log = LoggerFactory.getLogger(ApplyToolExecutionFailedApplicationService.class);

    private final TicketToolExecutionFailureGuardPort guardPort;
    private final TicketToolExecutionFailedRepository repository;
    private final TicketHistoryWriter historyWriter;
    private final AuditRecordPort auditRecordPort;
    private final OutboxEventRepository outboxEventRepository;
    private final ClockPort clock;
    private final TicketToolExecutionFailedAppliedEventMapper eventMapper;
    private final TicketTelemetry telemetry;

    public ApplyToolExecutionFailedApplicationService(
        TicketToolExecutionFailureGuardPort guardPort,
        TicketToolExecutionFailedRepository repository,
        TicketHistoryWriter historyWriter,
        AuditRecordPort auditRecordPort,
        OutboxEventRepository outboxEventRepository,
        ClockPort clock,
        TicketToolExecutionFailedAppliedEventMapper eventMapper,
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
    public ApplyToolExecutionFailedResult applyToolExecutionFailed(ApplyToolExecutionFailedCommand command) {
        var timer = telemetry.startApplyToolExecutionFailedTimer();
        try {
            if (repository.existsByToolExecutionId(command.toolExecutionId())) {
                logOutcome("duplicate", "toolExecutionId already recorded", command);
                telemetry.recordApplyToolExecutionFailedOutcome("duplicate");
                return ApplyToolExecutionFailedResult.duplicate(command.ticketId(), command.toolExecutionId());
            }

            Optional<TicketToolExecutionGuard> guardOpt = guardPort.loadGuard(command.ticketId(), command.workflowId(), command.actionId());
            if (guardOpt.isEmpty()) {
                logOutcome("stale", "no matching granted/auto-approved authorization", command);
                telemetry.recordApplyToolExecutionFailedOutcome("stale");
                return ApplyToolExecutionFailedResult.stale(command.ticketId(), command.toolExecutionId());
            }
            TicketToolExecutionGuard guard = guardOpt.get();

            if (isStale(guard, command)) {
                logOutcome("stale", "ticket no longer EXECUTING or references do not match", command);
                telemetry.recordApplyToolExecutionFailedOutcome("stale");
                return ApplyToolExecutionFailedResult.stale(command.ticketId(), command.toolExecutionId());
            }

            Instant now = clock.now();
            TicketToolExecutionFailedApplied applied = Ticket.applyToolExecutionFailed(
                command.ticketId(), guard.ticketStatus(), guard.assigneeId(), guard.ticketVersion(),
                command.workflowId(), command.actionId(), guard.authorizationReference(), command.toolExecutionId(),
                command.failureCode(), command.failureClass(), command.failedAt(), command.retryable(), command.eventId(), now
            );

            TicketToolExecutionFailedUpdateOutcome outcome = repository.applyToolExecutionFailed(new TicketToolExecutionFailedUpdate(
                command.ticketId(), guard.ticketVersion(), applied.newStatus(), command.workflowId(), command.actionId(),
                guard.authorizationReference(), command.toolExecutionId(), command.failureCode(), command.failureClass(),
                command.failedAt(), command.retryable(), command.eventId(), now
            ));
            if (outcome instanceof TicketToolExecutionFailedUpdateOutcome.Conflict) {
                logOutcome("stale", "concurrent modification detected at write time", command);
                telemetry.recordApplyToolExecutionFailedOutcome("stale");
                return ApplyToolExecutionFailedResult.stale(command.ticketId(), command.toolExecutionId());
            }

            historyWriter.append(new TicketStatusHistoryEntry(
                UUID.randomUUID(), command.ticketId(), applied.previousStatus(), applied.newStatus(),
                applied.transitionId(), applied.reasonCode(), "SERVICE", "tool-gateway-service",
                null, command.eventId(), command.workflowId(), applied.aggregateVersion(), now
            ));

            String traceId = currentTraceId();
            auditRecordPort.append(new AuditRecordEntry(
                UUID.randomUUID(), "BUSINESS_ACTION", "TOOL_EXECUTION_FAILED_APPLIED", "ALLOWED",
                "SERVICE", "tool-gateway-service", "tool-gateway-service",
                "TICKET", command.ticketId().toString(), guard.displayId().value(),
                applied.previousStatus().name(), applied.newStatus().name(),
                traceId, command.eventId(), "SUCCESS", "INTERNAL", now, null, null
            ));
            outboxEventRepository.append(eventMapper.map(applied, guard.supportQueueId(), traceId, command.correlationId(), command.eventId()));

            ApplyToolExecutionFailedResult result = ApplyToolExecutionFailedResult.applied(applied);
            telemetry.recordApplyToolExecutionFailedOutcome(result.outcome().name().toLowerCase());
            return result;
        } finally {
            telemetry.stopApplyToolExecutionFailedTimer(timer);
        }
    }

    private boolean isStale(TicketToolExecutionGuard guard, ApplyToolExecutionFailedCommand command) {
        if (guard.ticketStatus() != TicketStatus.EXECUTING) {
            return true;
        }
        if (!guard.authorizationReference().equals(command.authorizationReference())) {
            return true;
        }
        return command.actionType() != null && !command.actionType().isBlank() && !guard.actionType().equals(command.actionType());
    }

    private void logOutcome(String outcome, String reason, ApplyToolExecutionFailedCommand command) {
        log.info(
            "tool.execution.failed event processed as {}: {} (eventId={}, ticketId={}, toolExecutionId={}, workflowId={})",
            outcome, reason, command.eventId(), command.ticketId(), command.toolExecutionId(), command.workflowId()
        );
    }

    private String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }
}
