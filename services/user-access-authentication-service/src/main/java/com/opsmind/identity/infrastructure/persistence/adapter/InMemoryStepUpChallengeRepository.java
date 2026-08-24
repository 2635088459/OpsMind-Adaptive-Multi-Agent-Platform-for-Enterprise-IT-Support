package com.opsmind.identity.infrastructure.persistence.adapter;

import com.opsmind.identity.application.port.out.StepUpChallengeRepository;
import com.opsmind.identity.domain.stepup.StepUpChallenge;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** SPEC-UA-001-scoped placeholder — see {@link InMemoryUserIdentityRepository}'s own javadoc for the deferral this mirrors. */
@Repository
public class InMemoryStepUpChallengeRepository implements StepUpChallengeRepository {

    private final Map<String, StepUpChallenge> byId = new ConcurrentHashMap<>();

    @Override
    public Optional<StepUpChallenge> findById(String stepUpChallengeId) {
        return Optional.ofNullable(byId.get(stepUpChallengeId));
    }

    @Override
    public StepUpChallenge save(StepUpChallenge challenge) {
        byId.put(challenge.stepUpChallengeId(), challenge);
        return challenge;
    }
}
