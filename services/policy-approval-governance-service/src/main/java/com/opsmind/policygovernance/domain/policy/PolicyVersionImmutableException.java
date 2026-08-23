package com.opsmind.policygovernance.domain.policy;

import com.opsmind.policygovernance.domain.shared.DomainException;

/**
 * Thrown when code attempts to change the rules of a {@code PUBLISHED}
 * {@link PolicyVersion} in place. INV-PG-006: "Policy Versions Must Not
 * Rewrite History" — published versions are immutable, rule fixes require a
 * new version.
 */
public class PolicyVersionImmutableException extends DomainException {

    public PolicyVersionImmutableException(String policyVersionId) {
        super("policy version " + policyVersionId + " is PUBLISHED and immutable; create a new version instead");
    }

    @Override
    public String code() {
        return "POLICY_VERSION_IMMUTABLE";
    }
}
