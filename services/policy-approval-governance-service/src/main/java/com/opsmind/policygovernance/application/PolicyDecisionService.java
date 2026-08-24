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
import com.opsmind.policygovernance.domain.decision.PolicyDecisionCreatedEvent;
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
 *
 * <p>SPEC-PG-021 (10-failure-handling §Degraded Policy Mode): {@link
 * #failSafeDecision} marks the resulting {@link PolicyDecision#degraded()}
 * when the evaluator itself threw against a real effective version — see
 * that method's own javadoc for exactly which failure this covers and
 * which it deliberately does not.
 *
 * <p>SPEC-PG-032 (10-failure-handling §Degraded Policy Mode: "high-risk
 * mutation fails closed; low-risk read-only may use latest published
 * policy cache"): when the evaluator throws against a real effective
 * version AND {@link EvaluateDecisionCommand#readOnly()} is {@code true},
 * {@link #evaluate} calls {@link #degradedCacheFallbackDecision} instead of
 * {@link #failSafeDecision} — see that method's own javadoc. A caller that
 * never sets {@code readOnly} (the default) always still fails closed,
 * unchanged from SPEC-PG-021.
 *
 * <p>SPEC-PG-028: {@link #evaluate} stages the real, versioned {@link
 * PolicyDecisionCreatedEvent} instead of the generic {@code
 * governance.audit.decision_evaluated.v1} placeholder — the last governance
 * fact in this service still on the placeholder, graduated now because
 * SPEC-PG-028's own {@code policy.evaluation.requested.v1} async consumer
 * (04 Memory Knowledge) needs the real event to consume the result. A
 * duplicate {@code decisionKey + inputHash} still returns the existing
 * snapshot without staging anything (08-transaction-and-outbox §Policy
 * Decision: "does not create a new event") — unchanged.
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
                decision = command.readOnly()
                    ? degradedCacheFallbackDecision(command, effectiveVersion.get(), start)
                    : failSafeDecision(command, start, ReasonCode.EVALUATOR_UNAVAILABLE);
            }
        } else {
            metrics.recordPolicyEvaluationFailure();
            decision = failSafeDecision(command, start, ReasonCode.POLICY_VERSION_NOT_FOUND);
        }

        PolicyDecision saved = policyDecisionRepository.save(decision);
        if (saved.degraded()) {
            metrics.recordPolicyDegraded(saved.effect());
        }
        auditService.record(
            GovernanceAuditRecord.Action.DECISION_EVALUATED,
            command.subjectId(),
            "06",
            command.decisionKey(),
            saved.policyId(),
            saved.policyVersion(),
            "policy decision evaluated: " + saved.effect() + (saved.degraded() ? " (degraded=true)" : ""),
            command.correlationId(),
            command.causationId(),
            saved.ticketId(),
            null,
            saved.policyDecisionId(),
            PolicyDecisionCreatedEvent.from(saved, command.correlationId(), command.causationId())
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
            .addKeyValue("degraded", saved.degraded())
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

    /**
     * SPEC-PG-033 (goal: "poison decision review"). Every {@code
     * evaluationFailed} decision this service has ever persisted — see
     * {@code PolicyDecisionRepository#findEvaluationFailed}'s own javadoc
     * for why this is a review surface, not a repair action.
     */
    @Transactional(readOnly = true)
    public List<PolicyDecision> findPoisonDecisions() {
        return policyDecisionRepository.findEvaluationFailed();
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
            version.policyId(), String.valueOf(version.versionNumber()), now, null, false
        );
    }

    /**
     * SPEC-PG-032 (10-failure-handling §Degraded Policy Mode: "low-risk
     * read-only may use latest published policy cache"). Only ever called
     * when {@code command.readOnly()} is {@code true} AND a real effective
     * {@link PolicyVersion} was already found — the evaluator itself is
     * what crashed, not the version lookup, so "the cache" is simply the
     * already-fetched {@code version} this method binds the decision to,
     * satisfying "decisions without policy version are not allowed" even in
     * degraded mode (unlike {@link #failSafeDecision}'s {@code "NONE"}).
     * Unlike {@link #failSafeDecision}, {@code effect = ALLOW}: this is a
     * genuine, if degraded, governance judgment, not a failure, so {@code
     * evaluationFailed} stays {@code false} — 03-state-machine's {@code
     * EVALUATION_FAILED} terminal state does not apply to a decision that
     * was actually rendered, just via a fallback path. {@code riskLevel =
     * LOW}: 10-failure-handling names this fallback for "low-risk
     * read-only" specifically, and the caller declared exactly that via
     * {@code readOnly}.
     */
    private PolicyDecision degradedCacheFallbackDecision(EvaluateDecisionCommand command, PolicyVersion version, Instant now) {
        return new PolicyDecision(
            UUID.randomUUID().toString(), command.decisionKey(), command.inputHash(),
            command.subjectType(), command.subjectId(), command.actionType(),
            command.resourceType(), command.resourceId(), command.tenantId(),
            command.sourceDomain(), command.sourceRequestId(), command.ticketId(), command.workflowInstanceId(),
            DecisionEffect.ALLOW, RiskLevel.LOW, false, false, List.of(),
            List.of(ReasonCode.EVALUATOR_UNAVAILABLE, ReasonCode.DEGRADED_CACHE_FALLBACK),
            version.policyId(), String.valueOf(version.versionNumber()), now, null, true
        );
    }

    /**
     * Fail-safe snapshot for "no effective policy version" or "evaluator
     * threw" — {@code evaluationFailed = true} (03-state-machine's {@code
     * EVALUATION_FAILED} terminal state) with {@code effect = DENY}, never
     * {@code ALLOW}, so a caller that only reads {@code effect} still fails
     * closed. SPEC-PG-021: {@code degraded} is set only for {@link
     * ReasonCode#EVALUATOR_UNAVAILABLE} — see {@link PolicyDecision}'s own
     * javadoc for why {@code POLICY_VERSION_NOT_FOUND} is deliberately not
     * "degraded." SPEC-PG-032: this is now reached for {@code
     * EVALUATOR_UNAVAILABLE} only when {@code command.readOnly()} is {@code
     * false} — see {@link #degradedCacheFallbackDecision} for the {@code
     * true} branch.
     */
    private PolicyDecision failSafeDecision(EvaluateDecisionCommand command, Instant now, ReasonCode reasonCode) {
        return new PolicyDecision(
            UUID.randomUUID().toString(), command.decisionKey(), command.inputHash(),
            command.subjectType(), command.subjectId(), command.actionType(),
            command.resourceType(), command.resourceId(), command.tenantId(),
            command.sourceDomain(), command.sourceRequestId(), command.ticketId(), command.workflowInstanceId(),
            DecisionEffect.DENY, RiskLevel.HIGH, false, true, List.of(), List.of(reasonCode),
            command.policyId(), "NONE", now, null, reasonCode == ReasonCode.EVALUATOR_UNAVAILABLE
        );
    }
}
