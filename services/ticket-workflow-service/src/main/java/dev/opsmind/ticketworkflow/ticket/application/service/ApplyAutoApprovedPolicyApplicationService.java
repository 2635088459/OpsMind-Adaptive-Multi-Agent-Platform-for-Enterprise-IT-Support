package dev.opsmind.ticketworkflow.ticket.application.service;

import dev.opsmind.ticketworkflow.ticket.application.command.ApplyAutoApprovedPolicyCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyAutoApprovedPolicyResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketAutoApprovalAppliedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.TicketStatusHistoryEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.in.ApplyAutoApprovedPolicyUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAutoApprovedPolicyGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAutoApprovedPolicyGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAutoApprovedPolicyInsert;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAutoApprovedPolicyInsertOutcome;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketAutoApprovedPolicyRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketAutoApprovalApplied;
import dev.opsmind.ticketworkflow.ticket.domain.model.Ticket;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApprovalRiskLevel;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SPEC-TW-018 §1/domain-rules §1: {@code IN_PROGRESS -> IN_PROGRESS}
 * (transitionId {@code SM-020}). Structurally an INSERT flow, not an
 * UPDATE flow like {@code ApplyApprovalGranted/Rejected/ExpiredApplicationService}
 * (SPEC-TW-015/016/017): auto-approval never went through SPEC-TW-014's
 * request-approval, so there is no pre-existing open approval-request row
 * to guard against and update — this mints a brand new {@code
 * ticket_approval_requests} row with status {@code AUTO_APPROVED} directly,
 * mirroring {@code RequestApprovalApplicationService}'s (SPEC-TW-014)
 * guard-then-insert shape instead.
 * <p>
 * Classification: a row already recorded for this event's {@code
 * policyDecisionId} is {@code DUPLICATE} (checked at guard time via the
 * shared {@code approval_id} uniqueness, and again at write time for the
 * race where two deliveries both pass the guard read). A ticket not found,
 * or not currently {@code IN_PROGRESS} (SPEC-TW-018 acceptance-criteria:
 * "Ticket remains {@code IN_PROGRESS}"), is {@code STALE}. Since this event
 * carries no pre-registered workflow/action to match against — unlike
 * SPEC-TW-015/016/017 — the domain-specific trust check here is instead the
 * spec's own risk-eligibility rule (acceptance-criteria: "Missing policy
 * match cannot silently approve"): only {@code LOW}-risk decisions may be
 * silently auto-approved, so any other {@code riskLevel} is {@code
 * REJECTED_BUSINESS_RULE}.
 */
@Service
public class ApplyAutoApprovedPolicyApplicationService implements ApplyAutoApprovedPolicyUseCase {

    private static final Logger log = LoggerFactory.getLogger(ApplyAutoApprovedPolicyApplicationService.class);
    private static final ApprovalRiskLevel AUTO_APPROVABLE_RISK_LEVEL = ApprovalRiskLevel.LOW;

    private final TicketAutoApprovedPolicyGuardPort guardPort;
    private final TicketAutoApprovedPolicyRepository repository;
    private final TicketHistoryWriter historyWriter;
    private final AuditRecordPort auditRecordPort;
    private final OutboxEventRepository outboxEventRepository;
    private final ClockPort clock;
    private final TicketAutoApprovalAppliedEventMapper eventMapper;
    private final TicketTelemetry telemetry;

    public ApplyAutoApprovedPolicyApplicationService(
        TicketAutoApprovedPolicyGuardPort guardPort,
        TicketAutoApprovedPolicyRepository repository,
        TicketHistoryWriter historyWriter,
        AuditRecordPort auditRecordPort,
        OutboxEventRepository outboxEventRepository,
        ClockPort clock,
        TicketAutoApprovalAppliedEventMapper eventMapper,
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
    public ApplyAutoApprovedPolicyResult applyAutoApprovedPolicy(ApplyAutoApprovedPolicyCommand command) {
        var timer = telemetry.startApplyAutoApprovedPolicyTimer();
        try {
            Optional<TicketAutoApprovedPolicyGuard> guardOpt = guardPort.loadGuard(command.ticketId(), command.policyDecisionId());
            if (guardOpt.isEmpty()) {
                logOutcome("stale", "ticket not found", command);
                telemetry.recordApplyAutoApprovedPolicyOutcome("stale");
                return ApplyAutoApprovedPolicyResult.stale(command.ticketId(), null);
            }
            TicketAutoApprovedPolicyGuard guard = guardOpt.get();

            if (guard.existingApprovalRequestId() != null) {
                logOutcome("duplicate", "a request already exists for this policyDecisionId", command);
                telemetry.recordApplyAutoApprovedPolicyOutcome("duplicate");
                return ApplyAutoApprovedPolicyResult.duplicate(command.ticketId(), guard.existingApprovalRequestId());
            }
            if (guard.ticketStatus() != TicketStatus.IN_PROGRESS) {
                logOutcome("stale", "ticket is not IN_PROGRESS", command);
                telemetry.recordApplyAutoApprovedPolicyOutcome("stale");
                return ApplyAutoApprovedPolicyResult.stale(command.ticketId(), null);
            }
            if (command.riskLevel() != AUTO_APPROVABLE_RISK_LEVEL) {
                logOutcome("rejected_business_rule", "riskLevel is not eligible for silent auto-approval", command);
                telemetry.recordApplyAutoApprovedPolicyOutcome("rejected_business_rule");
                return ApplyAutoApprovedPolicyResult.rejectedBusinessRule(command.ticketId(), null);
            }

            Instant now = clock.now();
            UUID approvalRequestId = UUID.randomUUID();
            String authorizationReference = "auth-" + UUID.randomUUID();
            TicketAutoApprovalApplied applied = Ticket.applyAutoApprovedPolicy(
                command.ticketId(), guard.ticketStatus(), guard.assigneeId(), guard.ticketVersion(), approvalRequestId,
                command.workflowId(), command.actionId(), command.actionType(), command.riskLevel(),
                command.policyId(), command.policyVersion(), command.policyDecisionId(),
                authorizationReference, command.decidedAt(), command.eventId(), now
            );

            TicketAutoApprovedPolicyInsertOutcome outcome = repository.applyAutoApprovedPolicy(new TicketAutoApprovedPolicyInsert(
                command.ticketId(), guard.ticketVersion(), approvalRequestId, command.policyDecisionId(),
                command.workflowId(), command.actionId(), command.actionType(), command.riskLevel().name(),
                Map.of("source", "policy-auto-approval", "policyDecisionId", command.policyDecisionId()),
                command.policyId(), command.policyVersion(), authorizationReference, command.decidedAt(), command.eventId(), now
            ));
            if (outcome instanceof TicketAutoApprovedPolicyInsertOutcome.TicketConflict) {
                logOutcome("stale", "concurrent modification detected at write time", command);
                telemetry.recordApplyAutoApprovedPolicyOutcome("stale");
                return ApplyAutoApprovedPolicyResult.stale(command.ticketId(), null);
            }
            if (outcome instanceof TicketAutoApprovedPolicyInsertOutcome.DuplicateConflict) {
                logOutcome("duplicate", "concurrent delivery raced past the guard read for the same policyDecisionId", command);
                telemetry.recordApplyAutoApprovedPolicyOutcome("duplicate");
                return ApplyAutoApprovedPolicyResult.duplicate(command.ticketId(), null);
            }

            historyWriter.append(new TicketStatusHistoryEntry(
                UUID.randomUUID(), command.ticketId(), applied.previousStatus(), applied.newStatus(),
                applied.transitionId(), applied.reasonCode(), "SERVICE", "policy-approval-service",
                null, command.eventId(), applied.workflowId(), applied.aggregateVersion(), now
            ));

            String traceId = currentTraceId();
            auditRecordPort.append(new AuditRecordEntry(
                UUID.randomUUID(), "BUSINESS_ACTION", "AUTO_APPROVAL_APPLIED", "ALLOWED",
                "SERVICE", "policy-approval-service", "policy-approval-service",
                "TICKET", command.ticketId().toString(), guard.displayId().value(),
                applied.previousStatus().name(), applied.newStatus().name(),
                traceId, command.eventId(), "SUCCESS", "INTERNAL", now, null, null
            ));
            outboxEventRepository.append(eventMapper.map(applied, guard.supportQueueId(), traceId, command.correlationId(), command.eventId()));

            telemetry.recordApplyAutoApprovedPolicyOutcome("applied");
            return ApplyAutoApprovedPolicyResult.applied(applied);
        } finally {
            telemetry.stopApplyAutoApprovedPolicyTimer(timer);
        }
    }

    private void logOutcome(String outcome, String reason, ApplyAutoApprovedPolicyCommand command) {
        log.info(
            "policy.action_auto_approved event processed as {}: {} (eventId={}, ticketId={}, policyDecisionId={}, workflowId={})",
            outcome, reason, command.eventId(), command.ticketId(), command.policyDecisionId(), command.workflowId()
        );
    }

    private String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }
}
