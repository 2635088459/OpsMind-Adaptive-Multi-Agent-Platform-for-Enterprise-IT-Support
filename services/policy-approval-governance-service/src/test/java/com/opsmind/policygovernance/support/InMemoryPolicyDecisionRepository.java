package com.opsmind.policygovernance.support;

import com.opsmind.policygovernance.application.port.PolicyDecisionRepository;
import com.opsmind.policygovernance.domain.decision.PolicyDecision;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Fast, in-process test double for {@link PolicyDecisionRepository} — see {@link InMemoryPolicyRepository}. */
public class InMemoryPolicyDecisionRepository implements PolicyDecisionRepository {

    private final Map<String, PolicyDecision> byId = new ConcurrentHashMap<>();
    private final Map<String, String> idByDecisionKey = new ConcurrentHashMap<>();

    @Override
    public PolicyDecision save(PolicyDecision decision) {
        byId.put(decision.policyDecisionId(), decision);
        idByDecisionKey.putIfAbsent(decision.decisionKey(), decision.policyDecisionId());
        return decision;
    }

    @Override
    public Optional<PolicyDecision> findById(String policyDecisionId) {
        return Optional.ofNullable(byId.get(policyDecisionId));
    }

    @Override
    public Optional<PolicyDecision> findByDecisionKey(String decisionKey) {
        return Optional.ofNullable(idByDecisionKey.get(decisionKey)).map(byId::get);
    }
}
