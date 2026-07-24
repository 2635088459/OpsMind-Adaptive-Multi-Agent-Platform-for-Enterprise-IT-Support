package dev.opsmind.ticketworkflow.ticket.application.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * Computes the canonical idempotency request hash: SHA-256 over HTTP method,
 * normalized route template, actor scope, and a canonical (key-sorted) JSON
 * body. Excludes the JWT, trace headers, and request time, per BI-085/BI-088.
 */
@Component
public class RequestHashCalculator {

    private final ObjectMapper objectMapper;

    public RequestHashCalculator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String calculate(String httpMethod, String routeTemplate, String actorScope, Map<String, Object> canonicalBody) {
        try {
            String canonicalJson = objectMapper.writeValueAsString(new TreeMap<>(canonicalBody));
            String material = httpMethod + "\n" + routeTemplate + "\n" + actorScope + "\n" + canonicalJson;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to canonicalize request body for idempotency hashing", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
