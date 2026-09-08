package com.opsmind.policygovernance.application;

import com.opsmind.policygovernance.application.exception.PolicyNotFoundException;
import com.opsmind.policygovernance.application.port.PolicyRepository;
import com.opsmind.policygovernance.application.port.PolicyVersionRepository;
import com.opsmind.policygovernance.domain.policy.Policy;
import com.opsmind.policygovernance.domain.policy.PolicyVersion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * Read-only companion to {@link PolicyAdminService} — backs {@code
 * api.PolicyQueryController}'s {@code GET /api/v1/policies} and {@code
 * GET /api/v1/policies/{policyId}}. Before this, a drafted policy could only
 * be observed indirectly through a policy-decision evaluation; there was no
 * way for an operator (or the support-console admin UI) to list what
 * policies exist or see a policy's version history.
 */
@Service
public class PolicyQueryService {

    private final PolicyRepository policyRepository;
    private final PolicyVersionRepository policyVersionRepository;

    public PolicyQueryService(PolicyRepository policyRepository, PolicyVersionRepository policyVersionRepository) {
        this.policyRepository = policyRepository;
        this.policyVersionRepository = policyVersionRepository;
    }

    @Transactional(readOnly = true)
    public List<Policy> listPolicies() {
        return policyRepository.findAll().stream()
            .sorted(Comparator.comparing(Policy::policyName, String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    @Transactional(readOnly = true)
    public Policy getPolicy(String policyId) {
        return policyRepository.findById(policyId).orElseThrow(() -> new PolicyNotFoundException(policyId));
    }

    @Transactional(readOnly = true)
    public List<PolicyVersion> getPolicyVersions(String policyId) {
        return policyVersionRepository.findByPolicyId(policyId);
    }
}
