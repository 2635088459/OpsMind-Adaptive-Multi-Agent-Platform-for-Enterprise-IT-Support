package com.opsmind.identity.application.port.in;

import com.opsmind.identity.application.command.ConsumeStepUpChallengeCommand;
import com.opsmind.identity.application.command.RequestStepUpChallengeCommand;
import com.opsmind.identity.application.command.VerifyStepUpChallengeCommand;
import com.opsmind.identity.domain.stepup.StepUpChallenge;

/** 13-package-and-class-design §Primary Input Ports; 04-use-cases §Step-up. */
public interface ManageStepUpUseCase {

    StepUpChallenge request(RequestStepUpChallengeCommand command);

    StepUpChallenge verify(VerifyStepUpChallengeCommand command);

    StepUpChallenge consume(ConsumeStepUpChallengeCommand command);

    StepUpChallenge findById(String stepUpChallengeId);
}
