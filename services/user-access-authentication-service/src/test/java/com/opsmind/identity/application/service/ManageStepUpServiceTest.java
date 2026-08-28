package com.opsmind.identity.application.service;

import com.opsmind.identity.application.command.CancelStepUpChallengeCommand;
import com.opsmind.identity.application.command.ConsumeStepUpChallengeCommand;
import com.opsmind.identity.application.command.LinkUserIdentityCommand;
import com.opsmind.identity.application.command.RequestStepUpChallengeCommand;
import com.opsmind.identity.application.command.StartSessionCommand;
import com.opsmind.identity.application.command.VerifyStepUpChallengeCommand;
import com.opsmind.identity.application.exception.IdpUnavailableException;
import com.opsmind.identity.application.exception.StepUpBindingMismatchException;
import com.opsmind.identity.application.exception.StepUpEvidenceRejectedException;
import com.opsmind.identity.application.port.out.UserSessionRepository;
import com.opsmind.identity.domain.session.UserSession;
import com.opsmind.identity.domain.stepup.IllegalStepUpTransitionException;
import com.opsmind.identity.domain.stepup.StepUpChallenge;
import com.opsmind.identity.domain.stepup.StepUpStatus;
import com.opsmind.identity.domain.user.IdentityType;
import com.opsmind.identity.infrastructure.hashing.Sha256HashingAdapter;
import com.opsmind.identity.support.InMemoryAuditPort;
import com.opsmind.identity.support.FakeEventPublisherPort;
import com.opsmind.identity.support.InMemoryStepUpChallengeRepository;
import com.opsmind.identity.support.InMemoryUserIdentityRepository;
import com.opsmind.identity.support.InMemoryUserSessionRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class ManageStepUpServiceTest {

    private static final String ISSUER = "https://idp.example";
    private static final String SUBJECT = "sub-1";

    private final FixedClockPort clock = new FixedClockPort(Instant.parse("2026-01-01T00:00:00Z"));
    private final Sha256HashingAdapter hashingPort = new Sha256HashingAdapter();
    private final InMemoryUserIdentityRepository userIdentityRepository = new InMemoryUserIdentityRepository();
    private final UserSessionRepository userSessionRepository = new InMemoryUserSessionRepository();
    private final ProvisionUserService provisionUserService = new ProvisionUserService(userIdentityRepository, new InMemoryAuditPort(), new FakeEventPublisherPort(), clock);
    private final ManageSessionService sessionService = new ManageSessionService(userSessionRepository, userIdentityRepository, new InMemoryAuditPort(), new FakeEventPublisherPort(), new com.opsmind.identity.support.FakeOidcProviderPort(), new com.opsmind.identity.support.InMemoryIdentityMetricsPort(), clock);
    private final com.opsmind.identity.support.InMemoryIdentityMetricsPort identityMetricsPort = new com.opsmind.identity.support.InMemoryIdentityMetricsPort();
    private final com.opsmind.identity.support.FakeOidcProviderPort oidcProviderPort = new com.opsmind.identity.support.FakeOidcProviderPort();
    private final ManageStepUpService service = new ManageStepUpService(new InMemoryStepUpChallengeRepository(), userSessionRepository, new InMemoryAuditPort(), new FakeEventPublisherPort(), hashingPort, identityMetricsPort, oidcProviderPort, clock);

    private UserSession activeSession() {
        provisionUserService.link(new LinkUserIdentityCommand("tenant-1", ISSUER, SUBJECT, "alice", "Alice", null, IdentityType.HUMAN, "corr-setup"));
        StartSessionCommand startCommand = new StartSessionCommand(
            "tenant-1", ISSUER, SUBJECT, "idp-hash", "token-hash", "client-1", "urn:mace:acr:0",
            List.of("pwd"), clock.now(), "device-hash", Duration.ofHours(1), "corr-setup"
        );
        return sessionService.start(startCommand);
    }

    /** SPEC-UA-018: {@code nonceHash} is always caller-computed (mirrors {@code StepUpChallengeController}'s own real behavior) — the test keeps the raw value to present back as evidence at verify time. */
    private static final String RAW_NONCE = "test-raw-nonce";

    private StepUpChallenge requestChallenge(String userSessionId) {
        RequestStepUpChallengeCommand command = new RequestStepUpChallengeCommand(
            userSessionId, "approval:decide", "approval", "ap-1", "AAL2", List.of("otp"), 3, Duration.ofMinutes(5),
            hashingPort.hash(RAW_NONCE), "corr-1"
        );
        return service.request(command);
    }

    private VerifyStepUpChallengeCommand verifyCommand(StepUpChallenge challenge, String acr, List<String> amr) {
        return new VerifyStepUpChallengeCommand(challenge.stepUpChallengeId(), ISSUER, SUBJECT, acr, amr, RAW_NONCE, "corr-2");
    }

    private VerifyStepUpChallengeCommand realEvidence(StepUpChallenge challenge) {
        return verifyCommand(challenge, "AAL2", List.of("otp"));
    }

    @Test
    void requestCreatesAPendingChallengeBoundToTheSession() {
        UserSession session = activeSession();

        StepUpChallenge challenge = requestChallenge(session.userSessionId());

        assertThat(challenge.status()).isEqualTo(StepUpStatus.PENDING);
        assertThat(challenge.userSessionId()).isEqualTo(session.userSessionId());
        assertThat(identityMetricsPort.stepUpOutcomes()).containsExactly("REQUESTED");
    }

    /** SPEC-UA-032 (10-failure-handling: "Keycloak unavailable ... step-up ... return 503/fail closed"). */
    @Test
    void requestFailsClosedWhenTheIdpIsUnavailable() {
        UserSession session = activeSession();
        oidcProviderPort.setAvailable(false);

        assertThatThrownBy(() -> requestChallenge(session.userSessionId())).isInstanceOf(IdpUnavailableException.class);
        assertThat(identityMetricsPort.stepUpOutcomes()).containsExactly("REJECTED");
    }

    @Test
    void verifyThenConsumeReachesConsumed() {
        UserSession session = activeSession();
        StepUpChallenge challenge = requestChallenge(session.userSessionId());

        StepUpChallenge verified = service.verify(realEvidence(challenge));
        assertThat(verified.status()).isEqualTo(StepUpStatus.VERIFIED);

        StepUpChallenge consumed = service.consume(new ConsumeStepUpChallengeCommand(challenge.stepUpChallengeId(), "approval:decide", "approval", "ap-1", "corr-3"));
        assertThat(consumed.status()).isEqualTo(StepUpStatus.CONSUMED);
        assertThat(identityMetricsPort.stepUpOutcomes()).containsExactly("REQUESTED", "VERIFIED", "CONSUMED");
    }

    @Test
    void consumingTwiceFails() {
        UserSession session = activeSession();
        StepUpChallenge challenge = requestChallenge(session.userSessionId());
        service.verify(realEvidence(challenge));
        service.consume(new ConsumeStepUpChallengeCommand(challenge.stepUpChallengeId(), "approval:decide", "approval", "ap-1", "corr-3"));

        assertThatThrownBy(() -> service.consume(new ConsumeStepUpChallengeCommand(challenge.stepUpChallengeId(), "approval:decide", "approval", "ap-1", "corr-4")))
            .isInstanceOf(IllegalStepUpTransitionException.class);
    }

    @Test
    void consumingBeforeVerifyFails() {
        UserSession session = activeSession();
        StepUpChallenge challenge = requestChallenge(session.userSessionId());

        assertThatThrownBy(() -> service.consume(new ConsumeStepUpChallengeCommand(challenge.stepUpChallengeId(), "approval:decide", "approval", "ap-1", "corr-2")))
            .isInstanceOf(IllegalStepUpTransitionException.class);
    }

    @Test
    void reconcileExpiresPendingChallengesPastTheirOwnTimeout() {
        UserSession session = activeSession();
        StepUpChallenge challenge = requestChallenge(session.userSessionId());

        assertThat(service.reconcileExpired()).isZero();

        clock.advanceTo(challenge.expiresAt());
        assertThat(service.reconcileExpired()).isEqualTo(1);
        assertThat(service.findById(challenge.stepUpChallengeId()).status()).isEqualTo(StepUpStatus.EXPIRED);
    }

    /** SPEC-UA-017 (Step Up Challenge Lifecycle — 03-state-machine §StepUpChallenge: {@code PENDING --cancel--> CANCELLED}). */
    @Test
    void cancelWithdrawsAPendingChallengeBeforeItIsEverVerified() {
        UserSession session = activeSession();
        StepUpChallenge challenge = requestChallenge(session.userSessionId());

        StepUpChallenge cancelled = service.cancel(new CancelStepUpChallengeCommand(challenge.stepUpChallengeId(), "corr-2"));

        assertThat(cancelled.status()).isEqualTo(StepUpStatus.CANCELLED);
    }

    @Test
    void cancelIsIllegalOnceAlreadyVerified() {
        UserSession session = activeSession();
        StepUpChallenge challenge = requestChallenge(session.userSessionId());
        service.verify(realEvidence(challenge));

        assertThatThrownBy(() -> service.cancel(new CancelStepUpChallengeCommand(challenge.stepUpChallengeId(), "corr-3")))
            .isInstanceOf(IllegalStepUpTransitionException.class);
    }

    /** SPEC-UA-017 (INV-UA-005; 03-state-machine §StepUpChallenge: "Action/resource mismatch preserves state and writes a denial audit"). */
    @Test
    void consumingWithAMismatchedActionOrResourcePreservesStateAndNeverConsumes() {
        UserSession session = activeSession();
        StepUpChallenge challenge = requestChallenge(session.userSessionId());
        service.verify(realEvidence(challenge));

        assertThatThrownBy(() -> service.consume(new ConsumeStepUpChallengeCommand(challenge.stepUpChallengeId(), "approval:decide", "approval", "some-other-approval", "corr-3")))
            .isInstanceOf(StepUpBindingMismatchException.class);

        // Still VERIFIED, not CONSUMED and not attempt-counted — the mismatch never touched the challenge's own state.
        assertThat(service.findById(challenge.stepUpChallengeId()).status()).isEqualTo(StepUpStatus.VERIFIED);

        // The correctly-bound action/resource can still be consumed afterward.
        StepUpChallenge consumed = service.consume(new ConsumeStepUpChallengeCommand(challenge.stepUpChallengeId(), "approval:decide", "approval", "ap-1", "corr-4"));
        assertThat(consumed.status()).isEqualTo(StepUpStatus.CONSUMED);
    }

    /** SPEC-UA-018 (Step Up Proof Verification — INV-UA-005: "binds issuer, subject, session, action, resource, assurance, and expiry"). */
    @Test
    void verifyRejectsEvidenceFromADifferentSubjectAndPreservesAttemptCount() {
        UserSession session = activeSession();
        StepUpChallenge challenge = requestChallenge(session.userSessionId());

        assertThatThrownBy(() -> service.verify(new VerifyStepUpChallengeCommand(challenge.stepUpChallengeId(), ISSUER, "someone-else", "AAL2", List.of("otp"), RAW_NONCE, "corr-2")))
            .isInstanceOf(StepUpEvidenceRejectedException.class);

        assertThat(service.findById(challenge.stepUpChallengeId()).attemptCount()).isEqualTo(1);
    }

    @Test
    void verifyRejectsAWrongNonce() {
        UserSession session = activeSession();
        StepUpChallenge challenge = requestChallenge(session.userSessionId());

        assertThatThrownBy(() -> service.verify(new VerifyStepUpChallengeCommand(challenge.stepUpChallengeId(), ISSUER, SUBJECT, "AAL2", List.of("otp"), "wrong-nonce", "corr-2")))
            .isInstanceOf(StepUpEvidenceRejectedException.class);
        assertThat(identityMetricsPort.stepUpOutcomes()).containsExactly("REQUESTED", "REJECTED");
    }

    @Test
    void verifyRejectsAnAcrThatDoesNotMeetTheRequiredLevel() {
        UserSession session = activeSession();
        StepUpChallenge challenge = requestChallenge(session.userSessionId());

        assertThatThrownBy(() -> service.verify(verifyCommand(challenge, "urn:mace:acr:0", List.of("otp"))))
            .isInstanceOf(StepUpEvidenceRejectedException.class);
    }

    @Test
    void verifyRejectsWhenARequiredAmrMethodIsMissing() {
        UserSession session = activeSession();
        StepUpChallenge challenge = requestChallenge(session.userSessionId());

        assertThatThrownBy(() -> service.verify(verifyCommand(challenge, "AAL2", List.of("pwd"))))
            .isInstanceOf(StepUpEvidenceRejectedException.class);
    }

    @Test
    void verifySucceedsOnceRejectedThenRepresentedWithGenuineEvidence() {
        UserSession session = activeSession();
        StepUpChallenge challenge = requestChallenge(session.userSessionId());

        assertThatThrownBy(() -> service.verify(verifyCommand(challenge, "urn:mace:acr:0", List.of("otp"))))
            .isInstanceOf(StepUpEvidenceRejectedException.class);
        assertThat(service.findById(challenge.stepUpChallengeId()).attemptCount()).isEqualTo(1);

        StepUpChallenge verified = service.verify(realEvidence(challenge));
        assertThat(verified.status()).isEqualTo(StepUpStatus.VERIFIED);
    }
}
