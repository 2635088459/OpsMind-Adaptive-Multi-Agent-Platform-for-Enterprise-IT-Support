package com.opsmind.policygovernance.application;

import com.opsmind.policygovernance.application.command.RequestApprovalCommand;
import com.opsmind.policygovernance.domain.approval.ApprovalRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SPEC-PG-027 (04-use-cases §UC-PG-002, 06-event-contracts §Consumed
 * Events): 06's third real inbound event consumer, structurally identical
 * to {@link ToolApprovalRequiredEventHandler}/{@link
 * WorkflowApprovalRequiredEventHandler} — see {@code
 * ToolApprovalRequiredEventHandler}'s own javadoc for the full reasoning.
 */
@Service
public class TicketApprovalRequiredEventHandler {

    private static final Logger log = LoggerFactory.getLogger(TicketApprovalRequiredEventHandler.class);

    /** {@code processed_events.consumer_name} for this consumer — stable across restarts and redeploys, part of the dedup key. */
    public static final String CONSUMER_NAME = "policy-approval-governance-service.ticket-approval-required";

    private final ConsumedEventDeduplicationService deduplicationService;
    private final ApprovalService approvalService;

    public TicketApprovalRequiredEventHandler(ConsumedEventDeduplicationService deduplicationService, ApprovalService approvalService) {
        this.deduplicationService = deduplicationService;
        this.approvalService = approvalService;
    }

    /** A redelivered message (same {@code eventId}) is a silent no-op — see {@link ConsumedEventDeduplicationService}'s own javadoc. */
    @Transactional
    public void handle(String eventId, RequestApprovalCommand command) {
        deduplicationService.ifNew(eventId, CONSUMER_NAME, "ticket.approval.required.v1", () -> {
            ApprovalRequest saved = approvalService.request(command);
            log.atInfo()
                .addKeyValue("correlationId", command.correlationId())
                .addKeyValue("eventId", eventId)
                .addKeyValue("approvalRequestId", saved.approvalRequestId())
                .addKeyValue("ticketId", saved.ticketId())
                .addKeyValue("approvalType", saved.approvalType())
                .log("ticket.approval.required.v1 consumed");
            return saved;
        }, null);
    }
}
