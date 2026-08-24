package com.opsmind.policygovernance.application;

import com.opsmind.policygovernance.application.command.EvaluateDecisionCommand;
import com.opsmind.policygovernance.domain.decision.PolicyDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SPEC-PG-028 (04-use-cases §UC-PG-001, 06-event-contracts §Consumed
 * Events): 06's fourth real inbound event consumer, structurally identical
 * to {@link ToolApprovalRequiredEventHandler} in shape but targeting {@link
 * PolicyDecisionService#evaluate} instead of {@code ApprovalService#request}
 * — {@code policy.evaluation.requested.v1} is the asynchronous counterpart
 * of the synchronous risk decision API (UC-PG-001), not an approval
 * request.
 */
@Service
public class PolicyEvaluationRequestedEventHandler {

    private static final Logger log = LoggerFactory.getLogger(PolicyEvaluationRequestedEventHandler.class);

    /** {@code processed_events.consumer_name} for this consumer — stable across restarts and redeploys, part of the dedup key. */
    public static final String CONSUMER_NAME = "policy-approval-governance-service.policy-evaluation-requested";

    private final ConsumedEventDeduplicationService deduplicationService;
    private final PolicyDecisionService policyDecisionService;

    public PolicyEvaluationRequestedEventHandler(ConsumedEventDeduplicationService deduplicationService, PolicyDecisionService policyDecisionService) {
        this.deduplicationService = deduplicationService;
        this.policyDecisionService = policyDecisionService;
    }

    /**
     * A redelivered message (same {@code eventId}) is a silent no-op — see
     * {@link ConsumedEventDeduplicationService}'s own javadoc. A genuinely
     * different event for the same {@code decisionKey} still lands safely
     * on {@code PolicyDecisionService#evaluate}'s own pre-existing {@code
     * decisionKey + inputHash} idempotency (08-transaction-and-outbox
     * §Policy Decision).
     */
    @Transactional
    public void handle(String eventId, EvaluateDecisionCommand command) {
        deduplicationService.ifNew(eventId, CONSUMER_NAME, "policy.evaluation.requested.v1", () -> {
            PolicyDecision saved = policyDecisionService.evaluate(command);
            log.atInfo()
                .addKeyValue("correlationId", command.correlationId())
                .addKeyValue("eventId", eventId)
                .addKeyValue("policyDecisionId", saved.policyDecisionId())
                .addKeyValue("decisionKey", saved.decisionKey())
                .addKeyValue("effect", saved.effect())
                .log("policy.evaluation.requested.v1 consumed");
            return saved;
        }, null);
    }
}
