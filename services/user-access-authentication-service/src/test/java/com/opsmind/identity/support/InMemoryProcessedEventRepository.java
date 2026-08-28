package com.opsmind.identity.support;

import com.opsmind.identity.application.port.out.ProcessedEventRepository;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Fast, dependency-free application-service unit-test double for {@link ProcessedEventRepository}. Real persistence is {@code ProcessedEventPersistenceAdapter} (SPEC-UA-002/003). */
public class InMemoryProcessedEventRepository implements ProcessedEventRepository {

    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    @Override
    public boolean markProcessedIfNew(String eventId, String consumerName, String eventType) {
        return seen.add(eventId + "|" + consumerName);
    }
}
