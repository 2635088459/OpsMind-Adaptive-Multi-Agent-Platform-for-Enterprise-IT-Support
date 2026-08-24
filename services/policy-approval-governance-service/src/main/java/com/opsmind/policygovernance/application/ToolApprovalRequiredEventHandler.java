package com.opsmind.policygovernance.application;

import com.opsmind.policygovernance.application.command.RequestApprovalCommand;
import com.opsmind.policygovernance.domain.approval.ApprovalRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SPEC-PG-025 (04-use-cases §UC-PG-002, 06-event-contracts §Consumed
 * Events): the use-case behind 06's first real inbound event consumer —
 * turning a {@code tool.approval.required.v1} message into an {@link
 * ApprovalRequest} via the exact same {@link ApprovalService#request} every
 * synchronous {@code POST /api/v1/approval-requests} caller already uses.
 * Kept as its own application service (not folded into {@code
 * infrastructure.messaging.consumer.ToolApprovalRequiredEventConsumer}) so
 * the orchestration — dedup, then request — is testable without Spring or
 * RabbitMQ, mirroring every other application service in this codebase.
 */
@Service
public class ToolApprovalRequiredEventHandler {

    private static final Logger log = LoggerFactory.getLogger(ToolApprovalRequiredEventHandler.class);

    /** {@code processed_events.consumer_name} for this consumer — stable across restarts and redeploys, part of the dedup key. */
    public static final String CONSUMER_NAME = "policy-approval-governance-service.tool-approval-required";

    private final ConsumedEventDeduplicationService deduplicationService;
    private final ApprovalService approvalService;

    public ToolApprovalRequiredEventHandler(ConsumedEventDeduplicationService deduplicationService, ApprovalService approvalService) {
        this.deduplicationService = deduplicationService;
        this.approvalService = approvalService;
    }

    /**
     * A redelivered message (same {@code eventId}) is a silent no-op — see
     * {@link ConsumedEventDeduplicationService}'s own javadoc. A second,
     * genuinely different {@code tool.approval.required.v1} for the same
     * {@code toolRequestId} still lands on {@link ApprovalService#request}'s
     * own {@code requestKey} idempotency and returns the existing request
     * rather than creating a conflicting one.
     */
    @Transactional
    public void handle(String eventId, RequestApprovalCommand command) {
        deduplicationService.ifNew(eventId, CONSUMER_NAME, "tool.approval.required.v1", () -> {
            ApprovalRequest saved = approvalService.request(command);
            log.atInfo()
                .addKeyValue("correlationId", command.correlationId())
                .addKeyValue("eventId", eventId)
                .addKeyValue("approvalRequestId", saved.approvalRequestId())
                .addKeyValue("toolRequestId", saved.toolRequestId())
                .log("tool.approval.required.v1 consumed");
            return saved;
        }, null);
    }
}
