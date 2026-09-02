package com.opsmind.identity.application.port.out;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The 3 real, distinct outcomes of querying one Tempo tenant for one trace
 * ID (SPEC-SC-014) — deliberately NOT collapsed into a single {@code
 * Optional}: {@link NotFound} (a real, expected 404 — per ADR-0011 this
 * happens for every tenant not touched by a given cross-domain trace, not
 * an error) and {@link Unavailable} (Tempo/the network genuinely could not
 * be reached) must be told apart so {@code TraceWaterfallService} can
 * report an honest 503 rather than a false "trace not found" when Tempo
 * itself is down but some other tenant may have real data — the same
 * outage-vs-absence discipline SPEC-SC-007/019 already established for the
 * AI-log aggregation panel.
 */
public sealed interface TempoQueryResult {

    record Found(JsonNode payload) implements TempoQueryResult {
    }

    record NotFound() implements TempoQueryResult {
    }

    record Unavailable(String reason) implements TempoQueryResult {
    }
}
