package com.opsmind.policygovernance.support;

import com.opsmind.policygovernance.application.model.OutboxEventRecord;
import com.opsmind.policygovernance.application.port.MessageBrokerPublisherPort;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test double for {@link MessageBrokerPublisherPort}: records every
 * successfully "published" record, and fails the first {@code failCount}
 * publish attempts (for retry/dead-letter tests) before succeeding.
 */
public class FakeMessageBrokerPublisher implements MessageBrokerPublisherPort {

    private final List<OutboxEventRecord> published = new ArrayList<>();
    private final AtomicInteger remainingFailures;

    public FakeMessageBrokerPublisher() {
        this(0);
    }

    public FakeMessageBrokerPublisher(int failCount) {
        this.remainingFailures = new AtomicInteger(failCount);
    }

    @Override
    public void publish(OutboxEventRecord record) {
        if (remainingFailures.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
            throw new RuntimeException("simulated broker failure");
        }
        published.add(record);
    }

    public List<OutboxEventRecord> published() {
        return List.copyOf(published);
    }
}
