package com.opsmind.identity.support;

import com.opsmind.identity.application.port.out.StepUpChallengeRepository;
import com.opsmind.identity.domain.stepup.StepUpChallenge;
import com.opsmind.identity.domain.stepup.StepUpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Fast, dependency-free application-service unit-test double for {@link StepUpChallengeRepository}. Real persistence is {@code StepUpChallengePersistenceAdapter} (SPEC-UA-002/017). */
public class InMemoryStepUpChallengeRepository implements StepUpChallengeRepository {

    private final Map<String, StepUpChallenge> byId = new ConcurrentHashMap<>();

    @Override
    public Optional<StepUpChallenge> findById(String stepUpChallengeId) {
        return Optional.ofNullable(byId.get(stepUpChallengeId));
    }

    @Override
    public List<StepUpChallenge> findPendingExpired(Instant now) {
        return byId.values().stream()
            .filter(c -> c.status() == StepUpStatus.PENDING && !now.isBefore(c.expiresAt()))
            .toList();
    }

    /** Mirrors the real adapter's atomic conditional update semantics closely enough for application-service unit tests. */
    @Override
    public synchronized boolean tryConsume(String stepUpChallengeId, Instant now) {
        StepUpChallenge challenge = byId.get(stepUpChallengeId);
        if (challenge == null || challenge.status() != StepUpStatus.VERIFIED || !now.isBefore(challenge.expiresAt())) {
            return false;
        }
        byId.put(stepUpChallengeId, challenge.consume(now));
        return true;
    }

    @Override
    public StepUpChallenge save(StepUpChallenge challenge) {
        byId.put(challenge.stepUpChallengeId(), challenge);
        return challenge;
    }

    /** No transaction concept to honor here — same effect as {@link #save}; the real rollback semantics {@code saveIsolated} exists to survive only exist against real Postgres (SPEC-UA-023). */
    @Override
    public StepUpChallenge saveIsolated(StepUpChallenge challenge) {
        return save(challenge);
    }
}
