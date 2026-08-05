package dev.opsmind.ticketworkflow.ticket.application.service;

import dev.opsmind.ticketworkflow.ticket.application.command.ApplyApprovalRejectedCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyApprovalRejectedResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketApprovalRejectedAppliedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.TicketStatusHistoryEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.in.ApplyApprovalRejectedUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketApprovalRejectedRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketApprovalRejectedUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketApprovalRejectedUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketApprovalRejectionGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketApprovalRejectionGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketApprovalRejectedApplied;
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
 * SPEC-TW-016 §1/domain-rules §1: {@code WAITING_FOR_APPROVAL -> IN_PROGRESS}
 * (transitionId {@code SM-018}). Mirrors {@code
 * ApplyApprovalGrantedApplicationService}'s guard-then-write shape
 * (SPEC-TW-015) as closely as the two events' payload shapes allow: {@code
 * approval.rejected.v1} (06-event-contracts CON-007) carries no {@code
 * actionType} or expiry field, so the reference match here only checks
 * {@code workflowId}/{@code actionId}, and {@code actionType} for the
 * published event and the domain event is instead sourced from the guard's
 * own approval-request row (recorded by SPEC-TW-014 when the request was
 * opened) rather than from the untrusted-shape input event.
 * <p>
 * Classification order mirrors SPEC-TW-015's: an already-{@code REJECTED}
 * request is {@code DUPLICATE}; any other non-{@code OPEN} request, a
 * ticket that has moved off {@code WAITING_FOR_APPROVAL}, or a workflow/
 * action mismatch is {@code STALE} (ACK-without-advancing, not DLQ — see
 * SPEC-TW-016 API contract's outcome list). A rejection whose {@code
 * rejectedAt} predates the approval request's own {@code requestedAt} is
 * internally inconsistent for a trusted producer and is classified {@code
 * REJECTED_BUSINESS_RULE}, leaving the request {@code OPEN} so a
 * legitimate granted/rejected/expired event can still resolve it later —
 * the same trust-boundary sanity check SPEC-TW-015 applies to {@code
 * approvedAt <= expiresAt}, using the timestamp {@code approval.rejected.v1}
 * actually carries.
 */
@Service
public class ApplyApprovalRejectedApplicationService implements ApplyApprovalRejectedUseCase {

    private static final Logger log = LoggerFactory.getLogger(ApplyApprovalRejectedApplicationService.class);
    private static final String REJECTED_STATUS = "REJECTED";
    private static final String OPEN_STATUS = "OPEN";

    private final TicketApprovalRejectionGuardPort guardPort;
    private final TicketApprovalRejectedRepository repository;
    private final TicketHistoryWriter historyWriter;
    private final AuditRecordPort auditRecordPort;
    private final OutboxEventRepository outboxEventRepository;
    private final ClockPort clock;
    private final TicketApprovalRejectedAppliedEventMapper eventMapper;
    private final TicketTelemetry telemetry;

    public ApplyApprovalRejectedApplicationService(
        TicketApprovalRejectionGuardPort guardPort,
        TicketApprovalRejectedRepository repository,
        TicketHistoryWriter historyWriter,
        AuditRecordPort auditRecordPort,
        OutboxEventRepository outboxEventRepository,
        ClockPort clock,
        TicketApprovalRejectedAppliedEventMapper eventMapper,
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
    public ApplyApprovalRejectedResult applyApprovalRejected(ApplyApprovalRejectedCommand command) {
        var timer = telemetry.startApplyApprovalRejectedTimer();
        try {
            Optional<TicketApprovalRejectionGuard> guardOpt = guardPort.loadGuard(command.ticketId(), command.approvalId());
            if (guardOpt.isEmpty()) {
                logOutcome("stale", "no matching approval request", command);
                telemetry.recordApplyApprovalRejectedOutcome("stale");
                return ApplyApprovalRejectedResult.stale(command.ticketId(), null);
            }
            TicketApprovalRejectionGuard guard = guardOpt.get();

            if (REJECTED_STATUS.equals(guard.requestStatus())) {
                logOutcome("duplicate", "approval request already REJECTED", command);
                telemetry.recordApplyApprovalRejectedOutcome("duplicate");
                return ApplyApprovalRejectedResult.duplicate(command.ticketId(), guard.approvalRequestId());
            }
            if (isStale(guard, command)) {
                logOutcome("stale", "approval request no longer OPEN or references do not match", command);
                telemetry.recordApplyApprovalRejectedOutcome("stale");
                return ApplyApprovalRejectedResult.stale(command.ticketId(), guard.approvalRequestId());
            }
            if (command.rejectedAt().isBefore(guard.requestedAt())) {
                logOutcome("rejected_business_rule", "rejectedAt predates the approval request's requestedAt", command);
                telemetry.recordApplyApprovalRejectedOutcome("rejected_business_rule");
                return ApplyApprovalRejectedResult.rejectedBusinessRule(command.ticketId(), guard.approvalRequestId());
            }

            Instant now = clock.now();
            TicketApprovalRejectedApplied applied = Ticket.applyApprovalRejected(
                command.ticketId(), guard.ticketStatus(), guard.assigneeId(), guard.ticketVersion(),
                guard.approvalRequestId(), command.approvalId(), command.workflowId(), command.actionId(), guard.actionType(),
                command.rejectedByHash(), command.rejectedAt(), command.rejectionReason(), command.eventId(), now
            );

            TicketApprovalRejectedUpdateOutcome outcome = repository.applyApprovalRejected(new TicketApprovalRejectedUpdate(
                command.ticketId(), guard.ticketVersion(), guard.approvalRequestId(), command.approvalId(),
                command.rejectedByHash(), command.rejectedAt(), command.rejectionReason(), command.eventId(), now
            ));
            if (outcome instanceof TicketApprovalRejectedUpdateOutcome.Conflict) {
                logOutcome("stale", "concurrent modification detected at write time", command);
                telemetry.recordApplyApprovalRejectedOutcome("stale");
                return ApplyApprovalRejectedResult.stale(command.ticketId(), guard.approvalRequestId());
            }

            historyWriter.append(new TicketStatusHistoryEntry(
                UUID.randomUUID(), command.ticketId(), applied.previousStatus(), applied.newStatus(),
                applied.transitionId(), applied.reasonCode(), "SERVICE", "policy-approval-service",
                null, command.eventId(), applied.workflowId(), applied.aggregateVersion(), now
            ));

            String traceId = currentTraceId();
            auditRecordPort.append(new AuditRecordEntry(
                UUID.randomUUID(), "BUSINESS_ACTION", "APPROVAL_REJECTED_APPLIED", "ALLOWED",
                "SERVICE", "policy-approval-service", "policy-approval-service",
                "TICKET", command.ticketId().toString(), guard.displayId().value(),
                applied.previousStatus().name(), applied.newStatus().name(),
                traceId, command.eventId(), "SUCCESS", "INTERNAL", now, null, null
            ));
            outboxEventRepository.append(eventMapper.map(applied, guard.supportQueueId(), traceId, command.correlationId(), command.eventId()));

            telemetry.recordApplyApprovalRejectedOutcome("applied");
            return ApplyApprovalRejectedResult.applied(applied);
        } finally {
            telemetry.stopApplyApprovalRejectedTimer(timer);
        }
    }

    private boolean isStale(TicketApprovalRejectionGuard guard, ApplyApprovalRejectedCommand command) {
        if (!OPEN_STATUS.equals(guard.requestStatus())) {
            return true;
        }
        if (guard.ticketStatus() != TicketStatus.WAITING_FOR_APPROVAL) {
            return true;
        }
        return !guard.workflowId().equals(command.workflowId()) || !guard.actionId().equals(command.actionId());
    }

    private void logOutcome(String outcome, String reason, ApplyApprovalRejectedCommand command) {
        log.info(
            "approval.rejected event processed as {}: {} (eventId={}, ticketId={}, approvalId={}, workflowId={})",
            outcome, reason, command.eventId(), command.ticketId(), command.approvalId(), command.workflowId()
        );
    }

    private String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }
}
