package com.opsmind.policygovernance.support;

import com.opsmind.policygovernance.application.port.PolicyVersionRepository;
import com.opsmind.policygovernance.domain.policy.PolicyStatus;
import com.opsmind.policygovernance.domain.policy.PolicyVersion;

import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Fast, in-process test double for {@link PolicyVersionRepository} — see {@link InMemoryPolicyRepository}. */
public class InMemoryPolicyVersionRepository implements PolicyVersionRepository {

    private final Map<String, PolicyVersion> byId = new ConcurrentHashMap<>();

    @Override
    public PolicyVersion save(PolicyVersion policyVersion) {
        byId.put(policyVersion.policyVersionId(), policyVersion);
        return policyVersion;
    }

    @Override
    public Optional<PolicyVersion> findById(String policyVersionId) {
        return Optional.ofNullable(byId.get(policyVersionId));
    }

    @Override
    public Optional<PolicyVersion> findEffectiveVersion(String policyId, Instant asOf) {
        return byId.values().stream()
            .filter(v -> v.policyId().equals(policyId))
            .filter(v -> v.status() == PolicyStatus.PUBLISHED)
            .filter(v -> v.effectiveFrom() == null || !v.effectiveFrom().isAfter(asOf))
            .filter(v -> v.effectiveTo() == null || v.effectiveTo().isAfter(asOf))
            .max(Comparator.comparingInt(PolicyVersion::versionNumber));
    }

    @Override
    public Optional<PolicyVersion> findLatestVersion(String policyId) {
        return byId.values().stream()
            .filter(v -> v.policyId().equals(policyId))
            .max(Comparator.comparingInt(PolicyVersion::versionNumber));
    }

    @Override
    public Optional<PolicyVersion> findByPolicyIdAndVersionNumber(String policyId, int versionNumber) {
        return byId.values().stream()
            .filter(v -> v.policyId().equals(policyId) && v.versionNumber() == versionNumber)
            .findFirst();
    }
}
