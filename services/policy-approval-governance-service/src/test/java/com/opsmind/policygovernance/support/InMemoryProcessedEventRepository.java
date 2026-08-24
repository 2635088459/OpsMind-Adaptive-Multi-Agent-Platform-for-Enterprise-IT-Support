package com.opsmind.policygovernance.support;

import com.opsmind.policygovernance.application.model.ProcessedEventRecord;
import com.opsmind.policygovernance.application.port.ProcessedEventRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Fast, in-process test double for {@link ProcessedEventRepository} — see {@link InMemoryOutboxEventRepository}. */
public class InMemoryProcessedEventRepository implements ProcessedEventRepository {

    private final Map<String, ProcessedEventRecord> byKey = new ConcurrentHashMap<>();

    @Override
    public boolean markProcessedIfNew(String eventId, String consumerName, String eventType) {
        return byKey.putIfAbsent(key(eventId, consumerName), new ProcessedEventRecord(eventId, consumerName, eventType, Instant.now())) == null;
    }

    @Override
    public List<ProcessedEventRecord> findByEventId(String eventId) {
        return byKey.values().stream().filter(r -> r.eventId().equals(eventId)).toList();
    }

    @Override
    public boolean deleteIfExists(String eventId, String consumerName) {
        return byKey.remove(key(eventId, consumerName)) != null;
    }

    private static String key(String eventId, String consumerName) {
        return eventId + "|" + consumerName;
    }
}
