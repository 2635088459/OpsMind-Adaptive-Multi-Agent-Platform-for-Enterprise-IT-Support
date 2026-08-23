package com.opsmind.policygovernance.application;

import com.opsmind.policygovernance.application.model.OutboxEventRecord;
import com.opsmind.policygovernance.application.model.OutboxEventStatus;
import com.opsmind.policygovernance.domain.shared.SimpleGovernanceEvent;
import com.opsmind.policygovernance.support.FakeMessageBrokerPublisher;
import com.opsmind.policygovernance.support.InMemoryOutboxEventRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class OutboxDispatchServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final InMemoryOutboxEventRepository outboxEventRepository = new InMemoryOutboxEventRepository();

    @Test
    void stageWritesAPendingRowThatIsNotPublishedYet() {
        FakeMessageBrokerPublisher publisher = new FakeMessageBrokerPublisher();
        OutboxDispatchService service = new OutboxDispatchService(outboxEventRepository, publisher, clock);

        service.stage(SimpleGovernanceEvent.of("policy.decision.created.v1", "corr-1", null));

        assertThat(outboxEventRepository.all()).hasSize(1);
        assertThat(outboxEventRepository.all().get(0).status()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(publisher.published()).isEmpty();
    }

    @Test
    void publishPendingMarksASuccessfullyPublishedRowPublished() {
        FakeMessageBrokerPublisher publisher = new FakeMessageBrokerPublisher();
        OutboxDispatchService service = new OutboxDispatchService(outboxEventRepository, publisher, clock);
        service.stage(SimpleGovernanceEvent.of("approval.granted.v1", "corr-1", null));

        OutboxDispatchService.DrainResult result = service.publishPending();

        assertThat(result.published()).isEqualTo(1);
        assertThat(result.retried()).isZero();
        assertThat(result.deadLettered()).isZero();
        assertThat(publisher.published()).hasSize(1);
        OutboxEventRecord row = outboxEventRepository.all().get(0);
        assertThat(row.status()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(row.publishedAt()).isNotNull();
    }

    @Test
    void publishPendingRetriesATransientFailureRatherThanDeadLettering() {
        FakeMessageBrokerPublisher publisher = new FakeMessageBrokerPublisher(1);
        OutboxDispatchService service = new OutboxDispatchService(outboxEventRepository, publisher, clock);
        service.stage(SimpleGovernanceEvent.of("approval.denied.v1", "corr-1", null));

        OutboxDispatchService.DrainResult result = service.publishPending();

        assertThat(result.retried()).isEqualTo(1);
        assertThat(result.published()).isZero();
        OutboxEventRecord row = outboxEventRepository.all().get(0);
        assertThat(row.status()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(row.attemptCount()).isEqualTo(1);
        assertThat(row.availableAt()).isAfter(clock.instant());
    }

    @Test
    void publishPendingDeadLettersAfterExhaustingRetries() {
        MutableClock mutableClock = new MutableClock(clock.instant());
        FakeMessageBrokerPublisher publisher = new FakeMessageBrokerPublisher(Integer.MAX_VALUE);
        OutboxDispatchService service = new OutboxDispatchService(outboxEventRepository, publisher, mutableClock);
        service.stage(SimpleGovernanceEvent.of("approval.expired.v1", "corr-1", null));

        // Each failed attempt pushes the row's availableAt into the future by a
        // growing backoff; advance the clock past that before the next attempt so
        // every one of the 5 calls actually finds the row due.
        OutboxDispatchService.DrainResult result = new OutboxDispatchService.DrainResult(0, 0, 0);
        for (int i = 0; i < 5; i++) {
            mutableClock.advance(Duration.ofMinutes(10));
            result = service.publishPending();
        }

        assertThat(result.deadLettered()).isEqualTo(1);
        OutboxEventRecord row = outboxEventRepository.all().get(0);
        assertThat(row.status()).isEqualTo(OutboxEventStatus.FAILED);
    }

    /** A {@link Clock} that can be advanced on demand, for tests exercising backoff scheduling. */
    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
