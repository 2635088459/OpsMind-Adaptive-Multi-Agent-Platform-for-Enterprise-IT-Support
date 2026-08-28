package com.opsmind.identity.api.internal;

import com.opsmind.identity.api.IdentityRequestContext;
import com.opsmind.identity.application.command.CancelStepUpChallengeCommand;
import com.opsmind.identity.application.command.ConsumeStepUpChallengeCommand;
import com.opsmind.identity.application.command.RequestStepUpChallengeCommand;
import com.opsmind.identity.application.command.VerifyStepUpChallengeCommand;
import com.opsmind.identity.application.dto.ConsumeStepUpChallengeRequest;
import com.opsmind.identity.application.dto.RequestStepUpChallengeRequest;
import com.opsmind.identity.application.dto.StepUpChallengeView;
import com.opsmind.identity.application.dto.VerifyStepUpChallengeRequest;
import com.opsmind.identity.application.port.in.ManageStepUpUseCase;
import com.opsmind.identity.application.port.out.HashingPort;
import com.opsmind.identity.config.StepUpVerificationProperties;
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
import org.springframework.web.util.UriComponentsBuilder;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

/**
 * 05-api-contracts {@code POST /step-up/challenges}, {@code
 * /step-up/challenges/{id}/verify}, {@code /step-up/proofs/{handle}/consume}
 * (keyed here by challenge id directly rather than a separate opaque
 * handle). SPEC-UA-018: {@link #request} now generates a real
 * cryptographically random nonce (hashed before it is ever persisted,
 * mirroring how {@code BrowserLoginSuccessHandler} already hashes the
 * session {@code sid} in this exact layer) and returns a real {@code
 * redirect} pointing at this service's own step-up initiation endpoint —
 * see {@code StepUpAuthorizationRequestResolver}'s own javadoc for the rest
 * of that round trip.
 */
@RestController
public class StepUpChallengeController {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ManageStepUpUseCase manageStepUpUseCase;
    private final HashingPort hashingPort;

    public StepUpChallengeController(ManageStepUpUseCase manageStepUpUseCase, HashingPort hashingPort) {
        this.manageStepUpUseCase = manageStepUpUseCase;
        this.hashingPort = hashingPort;
    }

    @PostMapping("/internal/identity/v1/step-up/challenges")
    public ResponseEntity<StepUpChallengeView> request(
        @Valid @RequestBody RequestStepUpChallengeRequest request, HttpServletRequest httpRequest
    ) {
        String rawNonce = generateNonce();
        RequestStepUpChallengeCommand command = new RequestStepUpChallengeCommand(
            request.userSessionId(), request.action(), request.resourceType(), request.resourceId(),
            request.requiredAssuranceLevel(), request.requiredMethods(), request.maxAttempts(),
            Duration.ofSeconds(request.ttlSeconds()), hashingPort.hash(rawNonce), IdentityRequestContext.correlationId(httpRequest)
        );
        StepUpChallenge challenge = manageStepUpUseCase.request(command);

        UriComponentsBuilder redirect = UriComponentsBuilder
            .fromPath("/oauth2/authorization/" + StepUpVerificationProperties.REGISTRATION_ID)
            .queryParam("challengeId", challenge.stepUpChallengeId())
            .queryParam("nonce", rawNonce);
        if (challenge.requiredAssuranceLevel() != null && !challenge.requiredAssuranceLevel().isBlank()) {
            redirect.queryParam("acr", challenge.requiredAssuranceLevel());
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(StepUpChallengeView.from(challenge, redirect.build().toUriString()));
    }

    /** Only ever generated once per challenge and never reused — 256 bits from a {@link SecureRandom}, base64url-encoded. */
    private static String generateNonce() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @PostMapping("/internal/identity/v1/step-up/challenges/{challengeId}/verify")
    public ResponseEntity<StepUpChallengeView> verify(
        @PathVariable String challengeId, @Valid @RequestBody VerifyStepUpChallengeRequest request, HttpServletRequest httpRequest
    ) {
        VerifyStepUpChallengeCommand command = new VerifyStepUpChallengeCommand(
            challengeId, request.issuer(), request.subject(), request.acr(), request.amr(), request.nonce(),
            IdentityRequestContext.correlationId(httpRequest)
        );
        return ResponseEntity.ok(StepUpChallengeView.from(manageStepUpUseCase.verify(command)));
    }

    @PostMapping("/internal/identity/v1/step-up/challenges/{challengeId}/consume")
    public ResponseEntity<StepUpChallengeView> consume(
        @PathVariable String challengeId, @Valid @RequestBody ConsumeStepUpChallengeRequest request, HttpServletRequest httpRequest
    ) {
        ConsumeStepUpChallengeCommand command = new ConsumeStepUpChallengeCommand(
            challengeId, request.action(), request.resourceType(), request.resourceId(), IdentityRequestContext.correlationId(httpRequest)
        );
        return ResponseEntity.ok(StepUpChallengeView.from(manageStepUpUseCase.consume(command)));
    }

    /** 03-state-machine §StepUpChallenge: {@code PENDING --cancel--> CANCELLED} — withdraws a challenge before it is ever verified. */
    @PostMapping("/internal/identity/v1/step-up/challenges/{challengeId}/cancel")
    public ResponseEntity<StepUpChallengeView> cancel(@PathVariable String challengeId, HttpServletRequest httpRequest) {
        CancelStepUpChallengeCommand command = new CancelStepUpChallengeCommand(challengeId, IdentityRequestContext.correlationId(httpRequest));
        return ResponseEntity.ok(StepUpChallengeView.from(manageStepUpUseCase.cancel(command)));
    }

    @GetMapping("/internal/identity/v1/step-up/challenges/{challengeId}")
    public ResponseEntity<StepUpChallengeView> findById(@PathVariable String challengeId) {
        return ResponseEntity.ok(StepUpChallengeView.from(manageStepUpUseCase.findById(challengeId)));
    }
}
