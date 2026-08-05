package dev.opsmind.ticketworkflow.ticket.application.service;

import dev.opsmind.ticketworkflow.ticket.application.command.ApplyApprovalExpiredCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyApprovalExpiredResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketApprovalExpiredAppliedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.TicketStatusHistoryEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.in.ApplyApprovalExpiredUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketApprovalExpirationGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketApprovalExpirationGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketApprovalExpiredRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketApprovalExpiredUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketApprovalExpiredUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketApprovalExpiredApplied;
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
 * SPEC-TW-017 §1/domain-rules §1: {@code WAITING_FOR_APPROVAL -> IN_PROGRESS}
 * (transitionId {@code SM-019}). Mirrors {@code
 * ApplyApprovalRejectedApplicationService}'s guard-then-write shape
 * (SPEC-TW-016): {@code approval.expired.v1} (06-event-contracts CON-008)
 * carries no {@code actionType}, so the reference match here only checks
 * {@code workflowId}/{@code actionId}, and {@code actionType} for the
 * published event and the domain event is sourced from the guard's own
 * approval-request row rather than from the input event.
 * <p>
 * Classification order: an already-{@code EXPIRED} request is {@code
 * DUPLICATE}; any other non-{@code OPEN} request status — including {@code
 * GRANTED} or {@code REJECTED}, i.e. "Granted vs expired race is decided by
 * committed terminal state" (SPEC-TW-017 acceptance-criteria) — a ticket
 * that has moved off {@code WAITING_FOR_APPROVAL}, or a workflow/action
 * mismatch is {@code STALE} (ACK-without-advancing, not DLQ). A claimed
 * {@code expiredAt} that predates the approval request's own stored {@code
 * expiresAt} (SPEC-TW-014's {@code expires_at} column, when populated) is
 * internally inconsistent for a trusted producer and is classified {@code
 * REJECTED_BUSINESS_RULE} (acceptance-criteria's "{@code expiredAt >=
 * expiresAt}"), leaving the request {@code OPEN} so a legitimate granted/
 * rejected/expired event can still resolve it later.
 */
@Service
public class ApplyApprovalExpiredApplicationService implements ApplyApprovalExpiredUseCase {

    private static final Logger log = LoggerFactory.getLogger(ApplyApprovalExpiredApplicationService.class);
    private static final String EXPIRED_STATUS = "EXPIRED";
    private static final String OPEN_STATUS = "OPEN";

    private final TicketApprovalExpirationGuardPort guardPort;
    private final TicketApprovalExpiredRepository repository;
    private final TicketHistoryWriter historyWriter;
    private final AuditRecordPort auditRecordPort;
    private final OutboxEventRepository outboxEventRepository;
    private final ClockPort clock;
    private final TicketApprovalExpiredAppliedEventMapper eventMapper;
    private final TicketTelemetry telemetry;

    public ApplyApprovalExpiredApplicationService(
        TicketApprovalExpirationGuardPort guardPort,
        TicketApprovalExpiredRepository repository,
        TicketHistoryWriter historyWriter,
        AuditRecordPort auditRecordPort,
        OutboxEventRepository outboxEventRepository,
        ClockPort clock,
        TicketApprovalExpiredAppliedEventMapper eventMapper,
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
    public ApplyApprovalExpiredResult applyApprovalExpired(ApplyApprovalExpiredCommand command) {
        var timer = telemetry.startApplyApprovalExpiredTimer();
        try {
            Optional<TicketApprovalExpirationGuard> guardOpt = guardPort.loadGuard(command.ticketId(), command.approvalId());
            if (guardOpt.isEmpty()) {
                logOutcome("stale", "no matching approval request", command);
                telemetry.recordApplyApprovalExpiredOutcome("stale");
                return ApplyApprovalExpiredResult.stale(command.ticketId(), null);
            }
            TicketApprovalExpirationGuard guard = guardOpt.get();

            if (EXPIRED_STATUS.equals(guard.requestStatus())) {
                logOutcome("duplicate", "approval request already EXPIRED", command);
                telemetry.recordApplyApprovalExpiredOutcome("duplicate");
                return ApplyApprovalExpiredResult.duplicate(command.ticketId(), guard.approvalRequestId());
            }
            if (isStale(guard, command)) {
                logOutcome("stale", "approval request no longer OPEN or references do not match", command);
                telemetry.recordApplyApprovalExpiredOutcome("stale");
                return ApplyApprovalExpiredResult.stale(command.ticketId(), guard.approvalRequestId());
            }
            if (guard.expiresAt() != null && command.expiredAt().isBefore(guard.expiresAt())) {
                logOutcome("rejected_business_rule", "expiredAt predates the approval request's stored expiresAt", command);
                telemetry.recordApplyApprovalExpiredOutcome("rejected_business_rule");
                return ApplyApprovalExpiredResult.rejectedBusinessRule(command.ticketId(), guard.approvalRequestId());
            }

            Instant now = clock.now();
            TicketApprovalExpiredApplied applied = Ticket.applyApprovalExpired(
                command.ticketId(), guard.ticketStatus(), guard.assigneeId(), guard.ticketVersion(),
                guard.approvalRequestId(), command.approvalId(), command.workflowId(), command.actionId(), guard.actionType(),
                command.expiredAt(), command.expirationReason(), command.eventId(), now
            );

            TicketApprovalExpiredUpdateOutcome outcome = repository.applyApprovalExpired(new TicketApprovalExpiredUpdate(
                command.ticketId(), guard.ticketVersion(), guard.approvalRequestId(), command.approvalId(),
                command.expiredAt(), command.expirationReason(), command.eventId(), now
            ));
            if (outcome instanceof TicketApprovalExpiredUpdateOutcome.Conflict) {
                logOutcome("stale", "concurrent modification detected at write time", command);
                telemetry.recordApplyApprovalExpiredOutcome("stale");
                return ApplyApprovalExpiredResult.stale(command.ticketId(), guard.approvalRequestId());
            }

            historyWriter.append(new TicketStatusHistoryEntry(
                UUID.randomUUID(), command.ticketId(), applied.previousStatus(), applied.newStatus(),
                applied.transitionId(), applied.reasonCode(), "SERVICE", "policy-approval-service",
                null, command.eventId(), applied.workflowId(), applied.aggregateVersion(), now
            ));

            String traceId = currentTraceId();
            auditRecordPort.append(new AuditRecordEntry(
                UUID.randomUUID(), "BUSINESS_ACTION", "APPROVAL_EXPIRED_APPLIED", "ALLOWED",
                "SERVICE", "policy-approval-service", "policy-approval-service",
                "TICKET", command.ticketId().toString(), guard.displayId().value(),
                applied.previousStatus().name(), applied.newStatus().name(),
                traceId, command.eventId(), "SUCCESS", "INTERNAL", now, null, null
            ));
            outboxEventRepository.append(eventMapper.map(applied, guard.supportQueueId(), traceId, command.correlationId(), command.eventId()));

            telemetry.recordApplyApprovalExpiredOutcome("applied");
            return ApplyApprovalExpiredResult.applied(applied);
        } finally {
            telemetry.stopApplyApprovalExpiredTimer(timer);
        }
    }

    private boolean isStale(TicketApprovalExpirationGuard guard, ApplyApprovalExpiredCommand command) {
        if (!OPEN_STATUS.equals(guard.requestStatus())) {
            return true;
        }
        if (guard.ticketStatus() != TicketStatus.WAITING_FOR_APPROVAL) {
            return true;
        }
        return !guard.workflowId().equals(command.workflowId()) || !guard.actionId().equals(command.actionId());
    }

    private void logOutcome(String outcome, String reason, ApplyApprovalExpiredCommand command) {
        log.info(
            "approval.expired event processed as {}: {} (eventId={}, ticketId={}, approvalId={}, workflowId={})",
            outcome, reason, command.eventId(), command.ticketId(), command.approvalId(), command.workflowId()
        );
    }

    private String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }
}
