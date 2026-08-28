package com.opsmind.identity.application.port.out;

import com.opsmind.identity.domain.stepup.StepUpChallenge;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface StepUpChallengeRepository {

    Optional<StepUpChallenge> findById(String stepUpChallengeId);

    /** 03-state-machine: {@code PENDING} challenges past their own {@code expiresAt} — due for {@link StepUpChallenge#expire}. */
    List<StepUpChallenge> findPendingExpired(Instant now);

    /**
     * 09-concurrency-and-idempotency: {@code VERIFIED → CONSUMED} via a
     * single atomic conditional {@code UPDATE ... WHERE status='VERIFIED'
     * AND expires_at>now()} — at most one caller ever sees {@code true}.
     */
    boolean tryConsume(String stepUpChallengeId, Instant now);

    StepUpChallenge save(StepUpChallenge challenge);

    /**
     * SPEC-UA-023: {@code ManageStepUpService#verify}'s own catch block
     * records a failed attempt and then re-throws the very exception it is
     * handling — a real Testcontainers-backed HTTP round trip caught that
     * a plain {@link #save} there is silently discarded by Spring's
     * default rollback-on-unchecked-exception, since it shares {@code
     * verify()}'s own transactional boundary with the re-thrown exception.
     * A dedicated {@code REQUIRES_NEW} commit, independent of whatever the
     * caller ultimately does with that exception — mirrors {@code
     * SpringDataProcessedEventJpaRepository#insertIsolated}'s own identical
     * reasoning (a write that must survive regardless of the enclosing
     * transaction's own outcome).
     */
    StepUpChallenge saveIsolated(StepUpChallenge challenge);
}
