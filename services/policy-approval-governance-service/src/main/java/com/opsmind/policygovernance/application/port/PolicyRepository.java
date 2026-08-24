package com.opsmind.policygovernance.application.port;

import com.opsmind.policygovernance.domain.policy.Policy;

import java.util.List;
import java.util.Optional;

/** Port for {@link Policy} header persistence. Implemented by {@code infrastructure.persistence} adapters. */
public interface PolicyRepository {

    Policy save(Policy policy);

    Optional<Policy> findById(String policyId);

    /** SPEC-PG-033 (goal: "startup recovery workers" — 10-failure-handling §Recovery: "check policy version consistency"): every policy header this service knows about, for the recovery scan to walk. */
    List<Policy> findAll();
}
