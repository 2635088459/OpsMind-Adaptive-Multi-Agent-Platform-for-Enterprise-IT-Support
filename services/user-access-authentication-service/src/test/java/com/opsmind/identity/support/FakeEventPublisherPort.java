package com.opsmind.identity.support;

import com.opsmind.identity.application.port.out.EventPublisherPort;

import java.util.ArrayList;
import java.util.List;

/** Fast, dependency-free application-service unit-test double for {@link EventPublisherPort}. Real persistence is {@code OutboxEventPublisherAdapter} (SPEC-UA-003). */
public class FakeEventPublisherPort implements EventPublisherPort {

    private final List<Published> published = new ArrayList<>();

    @Override
    public void publish(String eventType, String aggregateType, String aggregateId, String payloadJson, String correlationId) {
        published.add(new Published(eventType, aggregateType, aggregateId, payloadJson, correlationId));
    }

    public List<Published> published() {
        return published;
    }

    public record Published(String eventType, String aggregateType, String aggregateId, String payloadJson, String correlationId) {
    }
}
