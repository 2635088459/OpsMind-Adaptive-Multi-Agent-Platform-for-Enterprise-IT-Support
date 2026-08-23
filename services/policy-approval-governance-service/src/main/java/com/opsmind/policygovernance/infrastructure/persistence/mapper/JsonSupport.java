package com.opsmind.policygovernance.infrastructure.persistence.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Serializes the small value-object lists (rules, constraints, reason
 * codes, conditions) that back each {@code jsonb} column
 * (07-data-model). A standalone {@link ObjectMapper} — not the web layer's
 * autoconfigured bean — so persistence stays independent of any HTTP
 * framework wiring; Java records serialize/deserialize natively given the
 * {@code -parameters} compiler flag this module's pom.xml already sets.
 */
final class JsonSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonSupport() {
    }

    static <T> String writeList(List<T> values) {
        try {
            return MAPPER.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize governance value objects to JSON", e);
        }
    }

    static <T> List<T> readList(String json, TypeReference<List<T>> typeReference) {
        try {
            return MAPPER.readValue(json, typeReference);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize governance value objects from JSON", e);
        }
    }
}
