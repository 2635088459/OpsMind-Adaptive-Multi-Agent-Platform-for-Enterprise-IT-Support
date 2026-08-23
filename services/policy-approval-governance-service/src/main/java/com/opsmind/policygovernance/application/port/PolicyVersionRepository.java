package com.opsmind.policygovernance.application.port;

import com.opsmind.policygovernance.domain.policy.PolicyVersion;

import java.time.Instant;
import java.util.Optional;

/** Port for {@link PolicyVersion} snapshot persistence. */
public interface PolicyVersionRepository {

    PolicyVersion save(PolicyVersion policyVersion);

    Optional<PolicyVersion> findById(String policyVersionId);

    /** Selects the {@code PUBLISHED} version effective at {@code asOf} (04-use-cases §UC-PG-001 step 2). */
    Optional<PolicyVersion> findEffectiveVersion(String policyId, Instant asOf);
}
