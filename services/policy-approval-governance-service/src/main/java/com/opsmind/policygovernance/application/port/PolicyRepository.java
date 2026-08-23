package com.opsmind.policygovernance.application.port;

import com.opsmind.policygovernance.domain.policy.Policy;

import java.util.Optional;

/** Port for {@link Policy} header persistence. Implemented by {@code infrastructure.persistence} adapters. */
public interface PolicyRepository {

    Policy save(Policy policy);

    Optional<Policy> findById(String policyId);
}
