package com.opsmind.policygovernance.application;

import com.opsmind.policygovernance.support.InMemoryProcessedEventRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class ConsumedEventDeduplicationServiceTest {

    private final InMemoryProcessedEventRepository processedEventRepository = new InMemoryProcessedEventRepository();
    private final ConsumedEventDeduplicationService service = new ConsumedEventDeduplicationService(processedEventRepository);

    @Test
    void runsTheActionExactlyOnceForANewEventId() {
        AtomicInteger calls = new AtomicInteger();

        String result = service.ifNew("evt-1", "consumer-a", "tool.approval.required.v1", () -> {
            calls.incrementAndGet();
            return "done";
        }, null);

        assertThat(result).isEqualTo("done");
        assertThat(calls.get()).isEqualTo(1);
    }

    /** 06-event-contracts §Idempotency: a redelivered message (same eventId) is a silent no-op, not an error. */
    @Test
    void skipsTheActionForAnAlreadyProcessedEventId() {
        AtomicInteger calls = new AtomicInteger();
        service.ifNew("evt-2", "consumer-a", "tool.approval.required.v1", () -> {
            calls.incrementAndGet();
            return "done";
        }, null);

        String secondResult = service.ifNew("evt-2", "consumer-a", "tool.approval.required.v1", () -> {
            calls.incrementAndGet();
            return "done";
        }, null);

        assertThat(secondResult).isNull();
        assertThat(calls.get()).isEqualTo(1);
    }

    /** Dedup is per (eventId, consumerName) — the same eventId delivered to a different consumer is genuinely new. */
    @Test
    void theSameEventIdIsIndependentlyProcessedByADifferentConsumer() {
        AtomicInteger calls = new AtomicInteger();
        service.ifNew("evt-3", "consumer-a", "tool.approval.required.v1", () -> {
            calls.incrementAndGet();
            return "done";
        }, null);

        String resultForConsumerB = service.ifNew("evt-3", "consumer-b", "tool.approval.required.v1", () -> {
            calls.incrementAndGet();
            return "done";
        }, null);

        assertThat(resultForConsumerB).isEqualTo("done");
        assertThat(calls.get()).isEqualTo(2);
    }
}
