package dev.opsmind.ticketworkflow.ticket.application.service;

import dev.opsmind.ticketworkflow.ticket.application.command.ApplyToolResultUnknownCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyToolResultUnknownResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketToolResultUnknownRecordedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.TicketStatusHistoryEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.in.ApplyToolResultUnknownUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolExecutionExistingRecord;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolExecutionGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolResultUnknownGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolResultUnknownRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolResultUnknownUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketToolResultUnknownUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketToolResultUnknownRecorded;
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
 * SPEC-TW-021 §1/domain-rules §1: {@code EXECUTING -> ESCALATED} (transitionId
 * {@code SM-024}). Mirrors {@code ApplyToolExecutionCompletedApplicationService}'s
 * (SPEC-TW-019) guard-then-write shape, but the {@code toolExecutionId}
 * existence check has three outcomes instead of two: no existing row means
 * this is the first outcome ever recorded for the attempt (proceed to the
 * guard); an existing row already classified {@code UNKNOWN} means this is
 * a plain replay ({@code DUPLICATE} — "duplicate does not escalate twice");
 * an existing row classified {@code COMPLETED}/{@code FAILED} means a
 * completed/failed outcome raced ahead of this unknown-result event (or
 * vice versa) — that is never silently overwritten, it is flagged {@code
 * CONFLICT_REQUIRES_RECONCILIATION} instead, without touching the ticket's
 * own status (SPEC-TW-019/020 already advanced it away from {@code
 * EXECUTING}; there is nothing left for this event to transition).
 */
@Service
public class ApplyToolResultUnknownApplicationService implements ApplyToolResultUnknownUseCase {

    private static final Logger log = LoggerFactory.getLogger(ApplyToolResultUnknownApplicationService.class);
    private static final String UNKNOWN_STATUS = "UNKNOWN";

    private final TicketToolResultUnknownGuardPort guardPort;
    private final TicketToolResultUnknownRepository repository;
    private final TicketHistoryWriter historyWriter;
    private final AuditRecordPort auditRecordPort;
    private final OutboxEventRepository outboxEventRepository;
    private final ClockPort clock;
    private final TicketToolResultUnknownRecordedEventMapper eventMapper;
    private final TicketTelemetry telemetry;

    public ApplyToolResultUnknownApplicationService(
        TicketToolResultUnknownGuardPort guardPort,
        TicketToolResultUnknownRepository repository,
        TicketHistoryWriter historyWriter,
        AuditRecordPort auditRecordPort,
        OutboxEventRepository outboxEventRepository,
        ClockPort clock,
        TicketToolResultUnknownRecordedEventMapper eventMapper,
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
    public ApplyToolResultUnknownResult applyToolResultUnknown(ApplyToolResultUnknownCommand command) {
        var timer = telemetry.startApplyToolResultUnknownTimer();
        try {
            Optional<TicketToolExecutionExistingRecord> existingOpt = repository.findExisting(command.toolExecutionId());
            if (existingOpt.isPresent()) {
                return classifyExisting(existingOpt.get(), command);
            }

            Optional<TicketToolExecutionGuard> guardOpt = guardPort.loadGuard(command.ticketId(), command.workflowId(), command.actionId());
            if (guardOpt.isEmpty()) {
                logOutcome("stale", "no matching granted/auto-approved authorization", command);
                telemetry.recordApplyToolResultUnknownOutcome("stale");
                return ApplyToolResultUnknownResult.stale(command.ticketId(), command.toolExecutionId());
            }
            TicketToolExecutionGuard guard = guardOpt.get();

            if (isStale(guard, command)) {
                logOutcome("stale", "ticket no longer EXECUTING or references do not match", command);
                telemetry.recordApplyToolResultUnknownOutcome("stale");
                return ApplyToolResultUnknownResult.stale(command.ticketId(), command.toolExecutionId());
            }

            Instant now = clock.now();
            TicketToolResultUnknownRecorded recorded = Ticket.applyToolResultUnknown(
                command.ticketId(), guard.ticketStatus(), guard.assigneeId(), guard.ticketVersion(),
                command.workflowId(), command.actionId(), guard.authorizationReference(), command.toolExecutionId(),
                command.unknownReason(), command.evidenceReferences(), command.observedAt(), command.eventId(), now
            );

            TicketToolResultUnknownUpdateOutcome outcome = repository.recordUnknownResult(new TicketToolResultUnknownUpdate(
                command.ticketId(), guard.ticketVersion(), command.workflowId(), command.actionId(), guard.authorizationReference(),
                command.toolExecutionId(), command.unknownReason(), command.evidenceReferences(), command.observedAt(), command.eventId(), now
            ));
            if (outcome instanceof TicketToolResultUnknownUpdateOutcome.Conflict) {
                logOutcome("stale", "concurrent modification detected at write time", command);
                telemetry.recordApplyToolResultUnknownOutcome("stale");
                return ApplyToolResultUnknownResult.stale(command.ticketId(), command.toolExecutionId());
            }

            historyWriter.append(new TicketStatusHistoryEntry(
                UUID.randomUUID(), command.ticketId(), recorded.previousStatus(), recorded.newStatus(),
                recorded.transitionId(), recorded.reasonCode(), "SERVICE", "tool-gateway-service",
                null, command.eventId(), command.workflowId(), recorded.aggregateVersion(), now
            ));

            String traceId = currentTraceId();
            auditRecordPort.append(new AuditRecordEntry(
                UUID.randomUUID(), "BUSINESS_ACTION", "TOOL_RESULT_UNKNOWN_RECORDED", "ALLOWED",
                "SERVICE", "tool-gateway-service", "tool-gateway-service",
                "TICKET", command.ticketId().toString(), guard.displayId().value(),
                recorded.previousStatus().name(), recorded.newStatus().name(),
                traceId, command.eventId(), "SUCCESS", "INTERNAL", now, null, null
            ));
            outboxEventRepository.append(eventMapper.map(recorded, guard.supportQueueId(), traceId, command.correlationId(), command.eventId()));

            telemetry.recordApplyToolResultUnknownOutcome("recorded_unknown");
            return ApplyToolResultUnknownResult.recorded(recorded);
        } finally {
            telemetry.stopApplyToolResultUnknownTimer(timer);
        }
    }

    /**
     * {@code toolExecutionId} was seen before this event arrived. An {@code
     * UNKNOWN} row is this exact event replayed — a plain {@code
     * DUPLICATE}. A {@code COMPLETED}/{@code FAILED} row means SPEC-TW-019/
     * 020 already recorded a different, terminal outcome for this attempt;
     * per domain-rules "late completed event cannot silently overwrite
     * unknown result" (and its mirror image here), this event must not
     * overwrite that row — it only flags it for reconciliation. A row that
     * belongs to a different ticket entirely is a cross-ticket anomaly this
     * event has no business touching; it is ACKed as {@code STALE} rather
     * than acted on.
     */
    private ApplyToolResultUnknownResult classifyExisting(TicketToolExecutionExistingRecord existing, ApplyToolResultUnknownCommand command) {
        if (!existing.ticketId().equals(command.ticketId().value())) {
            log.warn(
                "SECURITY_ALERT: tool.execution.result_unknown toolExecutionId={} already belongs to a different ticket (eventId={}, ticketId={})",
                command.toolExecutionId(), command.eventId(), command.ticketId()
            );
            telemetry.recordApplyToolResultUnknownOutcome("stale");
            return ApplyToolResultUnknownResult.stale(command.ticketId(), command.toolExecutionId());
        }
        if (UNKNOWN_STATUS.equals(existing.resultStatus())) {
            logOutcome("duplicate", "toolExecutionId already recorded as UNKNOWN", command);
            telemetry.recordApplyToolResultUnknownOutcome("duplicate");
            return ApplyToolResultUnknownResult.duplicate(command.ticketId(), command.toolExecutionId());
        }

        boolean flagged = repository.markConflictRequiresReconciliation(command.ticketId(), command.toolExecutionId(), command.eventId());
        if (!flagged) {
            logOutcome("stale", "existing record changed underneath the conflict flag update", command);
            telemetry.recordApplyToolResultUnknownOutcome("stale");
            return ApplyToolResultUnknownResult.stale(command.ticketId(), command.toolExecutionId());
        }

        Instant now = clock.now();
        auditRecordPort.append(new AuditRecordEntry(
            UUID.randomUUID(), "BUSINESS_ACTION", "TOOL_RESULT_UNKNOWN_CONFLICT_RECONCILIATION_REQUIRED", "ALLOWED",
            "SERVICE", "tool-gateway-service", "tool-gateway-service",
            "TICKET", command.ticketId().toString(), null,
            existing.resultStatus(), existing.resultStatus(),
            currentTraceId(), command.eventId(), "SUCCESS", "INTERNAL", now, null, null
        ));

        logOutcome("conflict_requires_reconciliation", "a " + existing.resultStatus() + " outcome was already recorded for this toolExecutionId", command);
        telemetry.recordApplyToolResultUnknownOutcome("conflict_requires_reconciliation");
        return ApplyToolResultUnknownResult.conflictRequiresReconciliation(command.ticketId(), command.toolExecutionId());
    }

    private boolean isStale(TicketToolExecutionGuard guard, ApplyToolResultUnknownCommand command) {
        if (guard.ticketStatus() != TicketStatus.EXECUTING) {
            return true;
        }
        if (!guard.authorizationReference().equals(command.authorizationReference())) {
            return true;
        }
        return command.actionType() != null && !command.actionType().isBlank() && !guard.actionType().equals(command.actionType());
    }

    private void logOutcome(String outcome, String reason, ApplyToolResultUnknownCommand command) {
        log.info(
            "tool.execution.result_unknown event processed as {}: {} (eventId={}, ticketId={}, toolExecutionId={}, workflowId={})",
            outcome, reason, command.eventId(), command.ticketId(), command.toolExecutionId(), command.workflowId()
        );
    }

    private String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }
}
