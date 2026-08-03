package dev.opsmind.ticketworkflow.ticket.application.service;

import dev.opsmind.ticketworkflow.ticket.application.command.ApplyApprovalGrantedCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyApprovalGrantedResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketApprovalGrantedAppliedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.TicketStatusHistoryEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.in.ApplyApprovalGrantedUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketApprovalGrantGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketApprovalGrantGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketApprovalGrantedRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketApprovalGrantedUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketApprovalGrantedUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketApprovalGrantedApplied;
import dev.opsmind.ticketworkflow.ticket.domain.model.Ticket;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
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
 * SPEC-TW-015 §1/domain-rules §1: {@code WAITING_FOR_APPROVAL -> IN_PROGRESS}
 * (transitionId {@code SM-017}). Mirrors {@code
 * RequestApprovalApplicationService}'s guard-then-write shape (SPEC-TW-014),
 * except the "guard" here also loads the open approval-request row (there is
 * no client-supplied {@code expectedVersion} for an event-driven consumer —
 * the ticket's own version, read in the same guard query, plays that role).
 * <p>
 * Classification order matches SPEC-TW-015 API-contract's four business
 * outcomes: an already-{@code GRANTED} request is {@code DUPLICATE}; any
 * other non-{@code OPEN} request, a ticket that has moved off {@code
 * WAITING_FOR_APPROVAL}, or a workflow/action/actionType mismatch is folded
 * into {@code STALE} (SPEC-TW-015's own API-contract enumerates only {@code
 * DLQ_SCHEMA_INVALID} and {@code DLQ_WRONG_PRODUCER} as DLQ outcomes — a
 * reference mismatch is therefore an ACK-without-advancing case, not a DLQ
 * case, even though the broader event-contracts LLD calls this "wrong
 * action / DLQ or security review"); an expired grant (SPEC-TW-011's
 * security trust boundary: {@code approvedAt <= expiresAt}) is {@code
 * REJECTED_BUSINESS_RULE} and leaves the request {@code OPEN} so a
 * legitimate rejected/expired event can still resolve it later.
 */
@Service
public class ApplyApprovalGrantedApplicationService implements ApplyApprovalGrantedUseCase {

    private static final Logger log = LoggerFactory.getLogger(ApplyApprovalGrantedApplicationService.class);
    private static final String GRANTED_STATUS = "GRANTED";
    private static final String OPEN_STATUS = "OPEN";

    private final TicketApprovalGrantGuardPort guardPort;
    private final TicketApprovalGrantedRepository repository;
    private final TicketHistoryWriter historyWriter;
    private final AuditRecordPort auditRecordPort;
    private final OutboxEventRepository outboxEventRepository;
    private final ClockPort clock;
    private final TicketApprovalGrantedAppliedEventMapper eventMapper;
    private final TicketTelemetry telemetry;

    public ApplyApprovalGrantedApplicationService(
        TicketApprovalGrantGuardPort guardPort,
        TicketApprovalGrantedRepository repository,
        TicketHistoryWriter historyWriter,
        AuditRecordPort auditRecordPort,
        OutboxEventRepository outboxEventRepository,
        ClockPort clock,
        TicketApprovalGrantedAppliedEventMapper eventMapper,
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
    public ApplyApprovalGrantedResult applyApprovalGranted(ApplyApprovalGrantedCommand command) {
        var timer = telemetry.startApplyApprovalGrantedTimer();
        try {
            Optional<TicketApprovalGrantGuard> guardOpt = guardPort.loadGuard(command.ticketId(), command.approvalId());
            if (guardOpt.isEmpty()) {
                logOutcome("stale", "no matching approval request", command);
                telemetry.recordApplyApprovalGrantedOutcome("stale");
                return ApplyApprovalGrantedResult.stale(command.ticketId(), null);
            }
            TicketApprovalGrantGuard guard = guardOpt.get();

            if (GRANTED_STATUS.equals(guard.requestStatus())) {
                logOutcome("duplicate", "approval request already GRANTED", command);
                telemetry.recordApplyApprovalGrantedOutcome("duplicate");
                return ApplyApprovalGrantedResult.duplicate(command.ticketId(), guard.approvalRequestId());
            }
            if (isStale(guard, command)) {
                logOutcome("stale", "approval request no longer OPEN or references do not match", command);
                telemetry.recordApplyApprovalGrantedOutcome("stale");
                return ApplyApprovalGrantedResult.stale(command.ticketId(), guard.approvalRequestId());
            }
            if (command.approvedAt().isAfter(command.expiresAt())) {
                logOutcome("rejected_business_rule", "approvedAt is after expiresAt", command);
                telemetry.recordApplyApprovalGrantedOutcome("rejected_business_rule");
                return ApplyApprovalGrantedResult.rejectedBusinessRule(command.ticketId(), guard.approvalRequestId());
            }

            Instant now = clock.now();
            String authorizationReference = "auth-" + UUID.randomUUID();
            TicketApprovalGrantedApplied applied = Ticket.applyApprovalGranted(
                command.ticketId(), guard.ticketStatus(), guard.assigneeId(), guard.ticketVersion(),
                guard.approvalRequestId(), command.approvalId(), command.workflowId(), command.actionId(), command.actionType(),
                command.approvedByHash(), command.approvedAt(), authorizationReference, command.eventId(), now
            );

            TicketApprovalGrantedUpdateOutcome outcome = repository.applyApprovalGranted(new TicketApprovalGrantedUpdate(
                command.ticketId(), guard.ticketVersion(), guard.approvalRequestId(), command.approvalId(),
                command.approvedByHash(), command.approvedAt(), authorizationReference, command.eventId(), now
            ));
            if (outcome instanceof TicketApprovalGrantedUpdateOutcome.Conflict) {
                logOutcome("stale", "concurrent modification detected at write time", command);
                telemetry.recordApplyApprovalGrantedOutcome("stale");
                return ApplyApprovalGrantedResult.stale(command.ticketId(), guard.approvalRequestId());
            }

            historyWriter.append(new TicketStatusHistoryEntry(
                UUID.randomUUID(), command.ticketId(), applied.previousStatus(), applied.newStatus(),
                applied.transitionId(), applied.reasonCode(), "SERVICE", "policy-approval-service",
                null, command.eventId(), applied.workflowId(), applied.aggregateVersion(), now
            ));

            String traceId = currentTraceId();
            auditRecordPort.append(new AuditRecordEntry(
                UUID.randomUUID(), "BUSINESS_ACTION", "APPROVAL_GRANTED_APPLIED", "ALLOWED",
                "SERVICE", "policy-approval-service", "policy-approval-service",
                "TICKET", command.ticketId().toString(), guard.displayId().value(),
                applied.previousStatus().name(), applied.newStatus().name(),
                traceId, command.eventId(), "SUCCESS", "INTERNAL", now, null, null
            ));
            outboxEventRepository.append(eventMapper.map(applied, guard.supportQueueId(), traceId, command.correlationId(), command.eventId()));

            telemetry.recordApplyApprovalGrantedOutcome("applied");
            return ApplyApprovalGrantedResult.applied(applied);
        } finally {
            telemetry.stopApplyApprovalGrantedTimer(timer);
        }
    }

    private boolean isStale(TicketApprovalGrantGuard guard, ApplyApprovalGrantedCommand command) {
        if (!OPEN_STATUS.equals(guard.requestStatus())) {
            return true;
        }
        if (guard.ticketStatus() != TicketStatus.WAITING_FOR_APPROVAL) {
            return true;
        }
        return !guard.workflowId().equals(command.workflowId())
            || !guard.actionId().equals(command.actionId())
            || !guard.actionType().equals(command.actionType());
    }

    private void logOutcome(String outcome, String reason, ApplyApprovalGrantedCommand command) {
        log.info(
            "approval.granted event processed as {}: {} (eventId={}, ticketId={}, approvalId={}, workflowId={})",
            outcome, reason, command.eventId(), command.ticketId(), command.approvalId(), command.workflowId()
        );
    }

    private String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }
}
