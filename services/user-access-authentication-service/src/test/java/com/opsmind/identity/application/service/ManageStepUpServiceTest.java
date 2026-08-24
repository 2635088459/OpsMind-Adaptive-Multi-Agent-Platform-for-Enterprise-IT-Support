package com.opsmind.identity.application.service;

import com.opsmind.identity.application.command.ConsumeStepUpChallengeCommand;
import com.opsmind.identity.application.command.LinkUserIdentityCommand;
import com.opsmind.identity.application.command.RequestStepUpChallengeCommand;
import com.opsmind.identity.application.command.StartSessionCommand;
import com.opsmind.identity.application.command.VerifyStepUpChallengeCommand;
import com.opsmind.identity.application.port.out.UserSessionRepository;
import com.opsmind.identity.domain.session.UserSession;
import com.opsmind.identity.domain.stepup.IllegalStepUpTransitionException;
import com.opsmind.identity.domain.stepup.StepUpChallenge;
import com.opsmind.identity.domain.stepup.StepUpStatus;
import com.opsmind.identity.domain.user.IdentityType;
import com.opsmind.identity.infrastructure.audit.InMemoryAuditPort;
import com.opsmind.identity.infrastructure.persistence.adapter.InMemoryStepUpChallengeRepository;
import com.opsmind.identity.infrastructure.persistence.adapter.InMemoryUserIdentityRepository;
import com.opsmind.identity.infrastructure.persistence.adapter.InMemoryUserSessionRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class ManageStepUpServiceTest {

    private final FixedClockPort clock = new FixedClockPort(Instant.parse("2026-01-01T00:00:00Z"));
    private final InMemoryUserIdentityRepository userIdentityRepository = new InMemoryUserIdentityRepository();
    private final UserSessionRepository userSessionRepository = new InMemoryUserSessionRepository();
    private final ProvisionUserService provisionUserService = new ProvisionUserService(userIdentityRepository, new InMemoryAuditPort(), clock);
    private final ManageSessionService sessionService = new ManageSessionService(userSessionRepository, userIdentityRepository, new InMemoryAuditPort(), clock);
    private final ManageStepUpService service = new ManageStepUpService(new InMemoryStepUpChallengeRepository(), userSessionRepository, new InMemoryAuditPort(), clock);

    private UserSession activeSession() {
        provisionUserService.link(new LinkUserIdentityCommand("tenant-1", "https://idp.example", "sub-1", "alice", "Alice", null, IdentityType.HUMAN, "corr-setup"));
        StartSessionCommand startCommand = new StartSessionCommand(
            "tenant-1", "https://idp.example", "sub-1", "idp-hash", "token-hash", "client-1", "urn:mace:acr:0",
            List.of("pwd"), clock.now(), "device-hash", Duration.ofHours(1), "corr-setup"
        );
        return sessionService.start(startCommand);
    }

    private RequestStepUpChallengeCommand requestCommand(String userSessionId) {
        return new RequestStepUpChallengeCommand(userSessionId, "approval:decide", "approval", "ap-1", "AAL2", List.of("otp"), 3, Duration.ofMinutes(5), "corr-1");
    }

    @Test
    void requestCreatesAPendingChallengeBoundToTheSession() {
        UserSession session = activeSession();

        StepUpChallenge challenge = service.request(requestCommand(session.userSessionId()));

        assertThat(challenge.status()).isEqualTo(StepUpStatus.PENDING);
        assertThat(challenge.userSessionId()).isEqualTo(session.userSessionId());
    }

    @Test
    void verifyThenConsumeReachesConsumed() {
        UserSession session = activeSession();
        StepUpChallenge challenge = service.request(requestCommand(session.userSessionId()));

        StepUpChallenge verified = service.verify(new VerifyStepUpChallengeCommand(challenge.stepUpChallengeId(), "proof-hash", "corr-2"));
        assertThat(verified.status()).isEqualTo(StepUpStatus.VERIFIED);

        StepUpChallenge consumed = service.consume(new ConsumeStepUpChallengeCommand(challenge.stepUpChallengeId(), "corr-3"));
        assertThat(consumed.status()).isEqualTo(StepUpStatus.CONSUMED);
    }

    @Test
    void consumingTwiceFails() {
        UserSession session = activeSession();
        StepUpChallenge challenge = service.request(requestCommand(session.userSessionId()));
        service.verify(new VerifyStepUpChallengeCommand(challenge.stepUpChallengeId(), "proof-hash", "corr-2"));
        service.consume(new ConsumeStepUpChallengeCommand(challenge.stepUpChallengeId(), "corr-3"));

        assertThatThrownBy(() -> service.consume(new ConsumeStepUpChallengeCommand(challenge.stepUpChallengeId(), "corr-4")))
            .isInstanceOf(IllegalStepUpTransitionException.class);
    }
}
