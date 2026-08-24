package com.opsmind.identity.api.internal;

import com.opsmind.identity.api.IdentityRequestContext;
import com.opsmind.identity.application.command.ConsumeStepUpChallengeCommand;
import com.opsmind.identity.application.command.RequestStepUpChallengeCommand;
import com.opsmind.identity.application.command.VerifyStepUpChallengeCommand;
import com.opsmind.identity.application.dto.RequestStepUpChallengeRequest;
import com.opsmind.identity.application.dto.StepUpChallengeView;
import com.opsmind.identity.application.dto.VerifyStepUpChallengeRequest;
import com.opsmind.identity.application.port.in.ManageStepUpUseCase;
import com.opsmind.identity.domain.stepup.StepUpChallenge;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/** 05-api-contracts {@code POST /step-up/challenges}, {@code /step-up/challenges/{id}/verify}, {@code /step-up/proofs/{handle}/consume} (keyed here by challenge id directly rather than a separate opaque handle). */
@RestController
public class StepUpChallengeController {

    private final ManageStepUpUseCase manageStepUpUseCase;

    public StepUpChallengeController(ManageStepUpUseCase manageStepUpUseCase) {
        this.manageStepUpUseCase = manageStepUpUseCase;
    }

    @PostMapping("/internal/identity/v1/step-up/challenges")
    public ResponseEntity<StepUpChallengeView> request(
        @Valid @RequestBody RequestStepUpChallengeRequest request, HttpServletRequest httpRequest
    ) {
        RequestStepUpChallengeCommand command = new RequestStepUpChallengeCommand(
            request.userSessionId(), request.action(), request.resourceType(), request.resourceId(),
            request.requiredAssuranceLevel(), request.requiredMethods(), request.maxAttempts(),
            Duration.ofSeconds(request.ttlSeconds()), IdentityRequestContext.correlationId(httpRequest)
        );
        StepUpChallenge challenge = manageStepUpUseCase.request(command);
        return ResponseEntity.status(HttpStatus.CREATED).body(StepUpChallengeView.from(challenge));
    }

    @PostMapping("/internal/identity/v1/step-up/challenges/{challengeId}/verify")
    public ResponseEntity<StepUpChallengeView> verify(
        @PathVariable String challengeId, @Valid @RequestBody VerifyStepUpChallengeRequest request, HttpServletRequest httpRequest
    ) {
        VerifyStepUpChallengeCommand command = new VerifyStepUpChallengeCommand(
            challengeId, request.proofIdHash(), IdentityRequestContext.correlationId(httpRequest)
        );
        return ResponseEntity.ok(StepUpChallengeView.from(manageStepUpUseCase.verify(command)));
    }

    @PostMapping("/internal/identity/v1/step-up/challenges/{challengeId}/consume")
    public ResponseEntity<StepUpChallengeView> consume(@PathVariable String challengeId, HttpServletRequest httpRequest) {
        ConsumeStepUpChallengeCommand command = new ConsumeStepUpChallengeCommand(challengeId, IdentityRequestContext.correlationId(httpRequest));
        return ResponseEntity.ok(StepUpChallengeView.from(manageStepUpUseCase.consume(command)));
    }

    @GetMapping("/internal/identity/v1/step-up/challenges/{challengeId}")
    public ResponseEntity<StepUpChallengeView> findById(@PathVariable String challengeId) {
        return ResponseEntity.ok(StepUpChallengeView.from(manageStepUpUseCase.findById(challengeId)));
    }
}
