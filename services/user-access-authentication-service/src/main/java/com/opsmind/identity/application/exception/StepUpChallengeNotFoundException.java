package com.opsmind.identity.application.exception;

public class StepUpChallengeNotFoundException extends RuntimeException {

    public StepUpChallengeNotFoundException(String stepUpChallengeId) {
        super("step-up challenge " + stepUpChallengeId + " was not found");
    }
}
