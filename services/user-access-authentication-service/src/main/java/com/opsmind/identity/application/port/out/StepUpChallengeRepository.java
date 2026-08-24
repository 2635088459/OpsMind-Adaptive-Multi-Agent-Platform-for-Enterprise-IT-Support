package com.opsmind.identity.application.port.out;

import com.opsmind.identity.domain.stepup.StepUpChallenge;

import java.util.Optional;

public interface StepUpChallengeRepository {

    Optional<StepUpChallenge> findById(String stepUpChallengeId);

    StepUpChallenge save(StepUpChallenge challenge);
}
