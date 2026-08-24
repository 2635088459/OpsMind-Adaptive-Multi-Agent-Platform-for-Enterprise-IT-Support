package com.opsmind.identity.infrastructure.persistence.adapter;

import com.opsmind.identity.application.port.out.AuthorizationDecisionRepository;
import com.opsmind.identity.domain.decision.AuthorizationDecision;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** SPEC-UA-001-scoped placeholder — see {@link InMemoryUserIdentityRepository}'s own javadoc for the deferral this mirrors. */
@Repository
public class InMemoryAuthorizationDecisionRepository implements AuthorizationDecisionRepository {

    private final Map<String, AuthorizationDecision> byId = new ConcurrentHashMap<>();

    @Override
    public Optional<AuthorizationDecision> findByDecisionKeyAndInputHash(String decisionKey, String inputHash) {
        return byId.values().stream()
            .filter(d -> d.decisionKey().equals(decisionKey) && d.inputHash().equals(inputHash))
            .findFirst();
    }

    @Override
    public Optional<AuthorizationDecision> findById(String decisionId) {
        return Optional.ofNullable(byId.get(decisionId));
    }

    @Override
    public AuthorizationDecision save(AuthorizationDecision decision) {
        byId.put(decision.decisionId(), decision);
        return decision;
    }
}
