package com.opsmind.policygovernance.application;

import com.opsmind.policygovernance.application.exception.PolicyNotFoundException;
import com.opsmind.policygovernance.domain.policy.Policy;
import com.opsmind.policygovernance.domain.policy.PolicyStatus;
import com.opsmind.policygovernance.domain.policy.PolicyVersion;
import com.opsmind.policygovernance.support.InMemoryPolicyRepository;
import com.opsmind.policygovernance.support.InMemoryPolicyVersionRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class PolicyQueryServiceTest {

    private final InMemoryPolicyRepository policyRepository = new InMemoryPolicyRepository();
    private final InMemoryPolicyVersionRepository versionRepository = new InMemoryPolicyVersionRepository();
    private final PolicyQueryService service = new PolicyQueryService(policyRepository, versionRepository);

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void listPoliciesReturnsEveryHeaderSortedByNameCaseInsensitively() {
        policyRepository.save(Policy.created("p-2", "beta policy", "tool-execution", "author-1", NOW));
        policyRepository.save(Policy.created("p-1", "Alpha policy", "global", "author-1", NOW));

        List<Policy> result = service.listPolicies();

        assertThat(result).extracting(Policy::policyName).containsExactly("Alpha policy", "beta policy");
    }

    @Test
    void getPolicyThrowsPolicyNotFoundForAnUnknownId() {
        assertThatThrownBy(() -> service.getPolicy("missing")).isInstanceOf(PolicyNotFoundException.class);
    }

    @Test
    void getPolicyVersionsReturnsTheFullHistoryNewestVersionFirst() {
        versionRepository.save(PolicyVersion.draft("pv-1", "p-1", 1, List.of(), "author-1"));
        versionRepository.save(
            PolicyVersion.draft("pv-2", "p-1", 2, List.of(), "author-1")
                .transitionTo(PolicyStatus.REVIEWING, "reviewer-1", null, NOW)
        );
        versionRepository.save(PolicyVersion.draft("pv-x", "other-policy", 1, List.of(), "author-1"));

        List<PolicyVersion> history = service.getPolicyVersions("p-1");

        assertThat(history).extracting(PolicyVersion::versionNumber).containsExactly(2, 1);
        assertThat(history).extracting(PolicyVersion::policyId).containsOnly("p-1");
    }
}
