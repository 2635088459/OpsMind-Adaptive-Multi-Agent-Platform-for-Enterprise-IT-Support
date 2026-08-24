package com.opsmind.identity.domain.stepup;

import com.opsmind.identity.domain.shared.TenantId;
import com.opsmind.identity.domain.user.ExternalSubject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StepUpChallengeTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final AuthorizationTarget TARGET = new AuthorizationTarget("approval:decide", "approval", "ap-1");

    private StepUpChallenge request(Instant expiresAt, int maxAttempts) {
        return StepUpChallenge.request(
            "c-1", "key-1", new TenantId("tenant-1"), new ExternalSubject("https://idp.example", "sub-1"), "s-1",
            TARGET, "AAL2", List.of("otp"), maxAttempts, "corr-1", NOW, expiresAt
        );
    }

    @Test
    void requestThenDispatchReachesPending() {
        StepUpChallenge pending = request(NOW.plusSeconds(120), 3).dispatch("nonce-hash", NOW.plusSeconds(1));

        assertThat(pending.status()).isEqualTo(StepUpStatus.PENDING);
    }

    @Test
    void verifyBeforeExpiryReachesVerified() {
        StepUpChallenge verified = request(NOW.plusSeconds(120), 3).dispatch("nonce-hash", NOW.plusSeconds(1)).verify("proof-hash", NOW.plusSeconds(30));

        assertThat(verified.status()).isEqualTo(StepUpStatus.VERIFIED);
        assertThat(verified.isVerified()).isTrue();
    }

    /** INV-UA-005: replay resistance. */
    @Test
    void consumeIsLegalExactlyOnce() {
        StepUpChallenge verified = request(NOW.plusSeconds(120), 3).dispatch("nonce-hash", NOW.plusSeconds(1)).verify("proof-hash", NOW.plusSeconds(10));
        StepUpChallenge consumed = verified.consume(NOW.plusSeconds(20));

        assertThat(consumed.status()).isEqualTo(StepUpStatus.CONSUMED);
        assertThatThrownBy(() -> consumed.consume(NOW.plusSeconds(30)))
            .isInstanceOf(IllegalStepUpTransitionException.class);
    }

    @Test
    void verifyAfterExpiryIsRejected() {
        StepUpChallenge pending = request(NOW.plusSeconds(60), 3).dispatch("nonce-hash", NOW.plusSeconds(1));

        assertThatThrownBy(() -> pending.verify("proof-hash", NOW.plusSeconds(61)))
            .isInstanceOf(IllegalStepUpTransitionException.class);
    }

    @Test
    void failAttemptTransitionsToFailedOnceMaxAttemptsReached() {
        StepUpChallenge pending = request(NOW.plusSeconds(120), 2).dispatch("nonce-hash", NOW.plusSeconds(1));

        StepUpChallenge afterFirstFailure = pending.failAttempt(NOW.plusSeconds(5));
        assertThat(afterFirstFailure.status()).isEqualTo(StepUpStatus.PENDING);
        assertThat(afterFirstFailure.attemptCount()).isEqualTo(1);

        StepUpChallenge afterSecondFailure = afterFirstFailure.failAttempt(NOW.plusSeconds(10));
        assertThat(afterSecondFailure.status()).isEqualTo(StepUpStatus.FAILED);
    }

    @Test
    void cancelIsLegalFromPending() {
        StepUpChallenge cancelled = request(NOW.plusSeconds(120), 3).dispatch("nonce-hash", NOW.plusSeconds(1)).cancel(NOW.plusSeconds(5));

        assertThat(cancelled.status()).isEqualTo(StepUpStatus.CANCELLED);
    }
}
