package com.opsmind.identity.application.port.out;

import com.opsmind.identity.domain.decision.AuthorizationDecision;

import java.util.Optional;

public interface AuthorizationDecisionRepository {

    /** 09-concurrency-and-idempotency: {@code (decisionKey, inputHash)} dedup. */
    Optional<AuthorizationDecision> findByDecisionKeyAndInputHash(String decisionKey, String inputHash);

    Optional<AuthorizationDecision> findById(String decisionId);

    AuthorizationDecision save(AuthorizationDecision decision);
}
