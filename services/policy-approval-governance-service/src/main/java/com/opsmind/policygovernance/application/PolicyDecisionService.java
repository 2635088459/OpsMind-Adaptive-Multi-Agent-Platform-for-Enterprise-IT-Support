package com.opsmind.policygovernance.application;

import com.opsmind.policygovernance.application.command.EvaluateDecisionCommand;
import com.opsmind.policygovernance.application.exception.DecisionKeyConflictException;
import com.opsmind.policygovernance.application.exception.PolicyDecisionNotFoundException;
import com.opsmind.policygovernance.application.port.GovernanceMetricsPort;
import com.opsmind.policygovernance.application.port.PolicyDecisionRepository;
import com.opsmind.policygovernance.application.port.PolicyVersionRepository;
import com.opsmind.policygovernance.application.port.RuleEvaluatorPort;
import com.opsmind.policygovernance.domain.audit.GovernanceAuditRecord;
import com.opsmind.policygovernance.domain.decision.DecisionEffect;
import com.opsmind.policygovernance.domain.decision.PolicyDecision;
import com.opsmind.policygovernance.domain.decision.ReasonCode;
import com.opsmind.policygovernance.domain.decision.RiskLevel;
import com.opsmind.policygovernance.domain.policy.PolicyVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * UC-PG-001 (Tool Gateway Requests Risk Decision): selects the effective
 * policy version, evaluates it, and persists an explainable, immutable
 * {@link PolicyDecision} snapshot. Never mutates Tool/Ticket/Workflow/Memory
 * state (INV-PG-001) and never defaults to {@code ALLOW} when the effective
 * version or the evaluator is unavailable (SPEC-PG-001 domain rule).
 *
 * <p>09-concurrency-and-idempotency §Policy Version Race: the effective
 * version is selected once, in this method's own transaction, and the
 * decision it produces is built from that already-fetched Java object — a
 * concurrently committed new version cannot retroactively change a decision
 * already in flight.
 */
@Service
public class PolicyDecisionService {

    private static final Logger log = LoggerFactory.getLogger(PolicyDecisionService.class);

    private final PolicyVersionRepository policyVersionRepository;
    private final PolicyDecisionRepository policyDecisionRepository;
    private final RuleEvaluatorPort ruleEvaluator;
    private final GovernanceAuditService auditService;
    private final GovernanceMetricsPort metrics;
    private final Clock clock;

    public PolicyDecisionService(
        PolicyVersionRepository policyVersionRepository,
        PolicyDecisionRepository policyDecisionRepository,
        RuleEvaluatorPort ruleEvaluator,
        GovernanceAuditService auditService,
        GovernanceMetricsPort metrics,
        Clock clock
    ) {
        this.policyVersionRepository = policyVersionRepository;
        this.policyDecisionRepository = policyDecisionRepository;
        this.ruleEvaluator = ruleEvaluator;
        this.auditService = auditService;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * SPEC-PG-002: the decision write and its audit record (§{@code
     * GovernanceAuditService#record}) commit atomically — "every governance
     * state transition must write audit/outbox in the same transaction."
     */
    @Transactional
    public PolicyDecision evaluate(EvaluateDecisionCommand command) {
        Instant start = clock.instant();

        // Idempotency (09-concurrency-and-idempotency §Duplicate Decision): same
        // inputHash replays the prior snapshot; a different inputHash under the same
        // decisionKey is a conflict — it must not silently overwrite a different fact
        // under one business key.
        Optional<PolicyDecision> existing = policyDecisionRepository.findByDecisionKey(command.decisionKey());
        if (existing.isPresent()) {
            if (existing.get().inputHash().equals(command.inputHash())) {
                return existing.get();
            }
            throw new DecisionKeyConflictException(command.decisionKey());
        }

        Optional<PolicyVersion> effectiveVersion = policyVersionRepository.findEffectiveVersion(command.policyId(), start);

        PolicyDecision decision;
        if (effectiveVersion.isPresent()) {
            try {
                decision = evaluateAgainst(command, effectiveVersion.get(), start);
            } catch (RuntimeException ex) {
                metrics.recordPolicyEvaluationFailure();
                log.warn(
                    "policy evaluator failed for decisionKey={} sourceDomain={} sourceRequestId={} correlationId={}",
                    command.decisionKey(), command.sourceDomain(), command.sourceRequestId(), command.correlationId(), ex
                );
                decision = failSafeDecision(command, start, ReasonCode.EVALUATOR_UNAVAILABLE);
            }
        } else {
            metrics.recordPolicyEvaluationFailure();
            decision = failSafeDecision(command, start, ReasonCode.POLICY_VERSION_NOT_FOUND);
        }

        PolicyDecision saved = policyDecisionRepository.save(decision);
        auditService.record(
            GovernanceAuditRecord.Action.DECISION_EVALUATED,
            command.subjectId(),
            "06",
            command.decisionKey(),
            saved.policyId(),
            saved.policyVersion(),
            "policy decision evaluated: " + saved.effect(),
            command.correlationId(),
            command.causationId()
        );

        Duration latency = Duration.between(start, clock.instant());
        metrics.recordPolicyDecision(saved.effect(), saved.riskLevel(), saved.sourceDomain(), latency);
        log.atInfo()
            .addKeyValue("correlationId", command.correlationId())
            .addKeyValue("causationId", command.causationId())
            .addKeyValue("policyDecisionId", saved.policyDecisionId())
            .addKeyValue("sourceDomain", saved.sourceDomain())
            .addKeyValue("sourceRequestId", saved.sourceRequestId())
            .addKeyValue("ticketId", saved.ticketId())
            .addKeyValue("workflowInstanceId", saved.workflowInstanceId())
            .addKeyValue("riskLevel", saved.riskLevel())
            .addKeyValue("effect", saved.effect())
            .addKeyValue("policyVersion", saved.policyVersion())
            .log("policy decision evaluated");
        return saved;
    }

    /**
     * SPEC-PG-006 / 05-api-contracts {@code GET /policy-decisions/{policyDecisionId}}:
     * lets a caller that already holds a {@code policyDecisionId} (e.g. from
     * the evaluate response, or via an audit lookup) re-fetch the immutable
     * snapshot without re-evaluating. Never mutates anything, so a plain
     * read-only transaction is enough.
     */
    @Transactional(readOnly = true)
    public PolicyDecision findById(String policyDecisionId) {
        return policyDecisionRepository.findById(policyDecisionId)
            .orElseThrow(() -> new PolicyDecisionNotFoundException(policyDecisionId));
    }

    private PolicyDecision evaluateAgainst(EvaluateDecisionCommand command, PolicyVersion version, Instant now) {
        RuleEvaluatorPort.Input input = new RuleEvaluatorPort.Input(
            command.subjectType(), command.subjectId(), command.actionType(),
            command.resourceType(), command.resourceId(), command.tenantId(), command.inputHash()
        );
        RuleEvaluatorPort.Result result = ruleEvaluator.evaluate(version, input);
        return new PolicyDecision(
            UUID.randomUUID().toString(), command.decisionKey(), command.inputHash(),
            command.subjectType(), command.subjectId(), command.actionType(),
            command.resourceType(), command.resourceId(), command.tenantId(),
            command.sourceDomain(), command.sourceRequestId(), command.ticketId(), command.workflowInstanceId(),
            result.effect(), result.riskLevel(), result.approvalRequired(), false,
            result.constraints(), result.reasonCodes(),
            version.policyId(), String.valueOf(version.versionNumber()), now, null
        );
    }

    /**
     * Fail-safe snapshot for "no effective policy version" — {@code
     * evaluationFailed = true} (03-state-machine's {@code EVALUATION_FAILED}
     * terminal state) with {@code effect = DENY}, never {@code ALLOW}, so a
     * caller that only reads {@code effect} still fails closed.
     */
    private PolicyDecision failSafeDecision(EvaluateDecisionCommand command, Instant now, ReasonCode reasonCode) {
        return new PolicyDecision(
            UUID.randomUUID().toString(), command.decisionKey(), command.inputHash(),
            command.subjectType(), command.subjectId(), command.actionType(),
            command.resourceType(), command.resourceId(), command.tenantId(),
            command.sourceDomain(), command.sourceRequestId(), command.ticketId(), command.workflowInstanceId(),
            DecisionEffect.DENY, RiskLevel.HIGH, false, true, List.of(), List.of(reasonCode),
            command.policyId(), "NONE", now, null
        );
    }
}
