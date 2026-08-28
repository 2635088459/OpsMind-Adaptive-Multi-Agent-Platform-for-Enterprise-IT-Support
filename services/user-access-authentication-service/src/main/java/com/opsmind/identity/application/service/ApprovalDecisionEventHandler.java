package com.opsmind.identity.application.service;

import com.opsmind.identity.application.command.ReconcileApprovalOutcomeCommand;
import com.opsmind.identity.application.port.in.ManageBreakGlassUseCase;
import com.opsmind.identity.domain.breakglass.ApprovalOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * SPEC-UA-028: the use-case behind the real inbound {@code
 * approval.granted.v1}/{@code approval.denied.v1}/{@code
 * approval.expired.v1} consumers — turning a domain-06 approval-outcome
 * message into a {@link ManageBreakGlassUseCase#reconcileApprovalOutcome}
 * call. Kept as its own application service (not folded into {@code
 * infrastructure.messaging.consumer.ApprovalDecisionEventConsumer}) so the
 * orchestration — dedup, then reconcile — is testable without Spring or
 * RabbitMQ, mirroring every other application service in this codebase
 * (and policy-approval-governance-service's own {@code
 * ToolApprovalRequiredEventHandler}).
 */
@Service
public class ApprovalDecisionEventHandler {

    private static final Logger log = LoggerFactory.getLogger(ApprovalDecisionEventHandler.class);

    /** {@code processed_events.consumer_name} — stable across restarts and redeploys, part of the dedup key. */
    public static final String CONSUMER_NAME = "user-access-authentication-service.approval-decision";

    private final ConsumedEventDeduplicationService deduplicationService;
    private final ManageBreakGlassUseCase manageBreakGlassUseCase;

    public ApprovalDecisionEventHandler(ConsumedEventDeduplicationService deduplicationService, ManageBreakGlassUseCase manageBreakGlassUseCase) {
        this.deduplicationService = deduplicationService;
        this.manageBreakGlassUseCase = manageBreakGlassUseCase;
    }

    /** A redelivered message (same {@code eventId}) is a silent no-op — see {@link ConsumedEventDeduplicationService}'s own javadoc. */
    public void handle(String eventId, String eventType, String approvalRequestId, ApprovalOutcome outcome, String correlationId) {
        deduplicationService.ifNew(eventId, CONSUMER_NAME, eventType, () -> {
            manageBreakGlassUseCase.reconcileApprovalOutcome(new ReconcileApprovalOutcomeCommand(approvalRequestId, outcome, correlationId));
            log.atInfo()
                .addKeyValue("correlationId", correlationId)
                .addKeyValue("eventId", eventId)
                .addKeyValue("approvalRequestId", approvalRequestId)
                .addKeyValue("outcome", outcome)
                .log("{} consumed", eventType);
        });
    }
}
