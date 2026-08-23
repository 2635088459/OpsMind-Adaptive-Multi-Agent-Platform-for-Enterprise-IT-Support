package com.opsmind.policygovernance.support;

import com.opsmind.policygovernance.application.port.PolicyRepository;
import com.opsmind.policygovernance.domain.policy.Policy;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fast, in-process test double for {@link PolicyRepository} — since
 * SPEC-PG-002, application-layer unit tests use this instead of a real
 * Postgres adapter; {@code PolicyPersistenceAdapter} (Testcontainers-backed
 * IT tests) covers the real JPA/Postgres path.
 */
public class InMemoryPolicyRepository implements PolicyRepository {

    private final Map<String, Policy> byId = new ConcurrentHashMap<>();

    @Override
    public Policy save(Policy policy) {
        byId.put(policy.policyId(), policy);
        return policy;
    }

    @Override
    public Optional<Policy> findById(String policyId) {
        return Optional.ofNullable(byId.get(policyId));
    }
}
