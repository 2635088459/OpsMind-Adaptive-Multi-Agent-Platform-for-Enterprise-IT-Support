package com.opsmind.identity.application.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.opsmind.identity.application.dto.TraceWaterfallView;
import com.opsmind.identity.application.exception.TraceNotFoundException;
import com.opsmind.identity.application.exception.TraceQueryUnavailableException;
import com.opsmind.identity.application.port.out.TempoQueryResult;
import com.opsmind.identity.application.port.out.TraceQueryPort;
import com.opsmind.identity.config.TempoQueryProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Comparator;

/**
 * SPEC-SC-014: queries every configured tenant (ADR-0011 — a cross-domain
 * trace may legitimately be split across several) and merges whatever real
 * spans come back into one waterfall, honestly reporting which tenants had
 * data and which could not be reached at all (SPEC-SC-007/019's own
 * outage-vs-absence discipline).
 */
@Service
public class TraceWaterfallService {

    private final TraceQueryPort traceQueryPort;
    private final TempoQueryProperties properties;

    public TraceWaterfallService(TraceQueryPort traceQueryPort, TempoQueryProperties properties) {
        this.traceQueryPort = traceQueryPort;
        this.properties = properties;
    }

    public TraceWaterfallView fetch(String traceId) {
        List<TraceWaterfallView.SpanView> rawSpans = new ArrayList<>();
        List<String> foundIn = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        List<long[]> spanTimes = new ArrayList<>(); // parallel [startNanos, endNanos] per rawSpans entry, pre-offset

        for (String tenant : properties.tenants()) {
            TempoQueryResult result = traceQueryPort.queryTrace(properties.baseUrl(), traceId, tenant);
            switch (result) {
                case TempoQueryResult.Found found -> {
                    foundIn.add(tenant);
                    parseBatches(found.payload(), tenant, rawSpans, spanTimes);
                }
                case TempoQueryResult.NotFound ignored -> {
                    // expected — this tenant simply wasn't touched by this trace (ADR-0011).
                }
                case TempoQueryResult.Unavailable u -> unavailable.add(tenant);
            }
        }

        if (rawSpans.isEmpty()) {
            if (!unavailable.isEmpty()) {
                throw new TraceQueryUnavailableException(traceId);
            }
            throw new TraceNotFoundException(traceId);
        }

        long minStartNanos = spanTimes.stream().mapToLong(t -> t[0]).min().orElseThrow();
        List<TraceWaterfallView.SpanView> spans = new ArrayList<>();
        for (int i = 0; i < rawSpans.size(); i++) {
            TraceWaterfallView.SpanView raw = rawSpans.get(i);
            long[] times = spanTimes.get(i);
            long offsetMs = (times[0] - minStartNanos) / 1_000_000;
            long durationMs = Math.max(0, (times[1] - times[0]) / 1_000_000);
            spans.add(new TraceWaterfallView.SpanView(
                raw.spanId(), raw.parentSpanId(), raw.name(), raw.serviceName(), raw.tenant(), raw.kind(), raw.statusCode(),
                offsetMs, durationMs
            ));
        }
        spans.sort(Comparator.comparingLong(TraceWaterfallView.SpanView::startOffsetMs));

        return new TraceWaterfallView(traceId, spans, List.copyOf(foundIn), List.copyOf(unavailable));
    }

    /**
     * Parses Tempo's own real response shape — LIVE-confirmed (not assumed
     * from docs) raw OTLP-JSON: {@code batches[].resource.attributes[]} +
     * {@code batches[].scopeSpans[].spans[]}, with {@code traceId}/{@code
     * spanId}/{@code parentSpanId} as base64 (proto3 bytes-field JSON
     * convention) and {@code startTimeUnixNano}/{@code endTimeUnixNano} as
     * JSON strings holding a nanosecond epoch value — a `long` losslessly
     * holds it, a JS {@code Number} would not, which is exactly why this
     * parsing happens here rather than being forwarded raw to the browser.
     */
    private void parseBatches(JsonNode root, String tenant, List<TraceWaterfallView.SpanView> outSpans, List<long[]> outTimes) {
        JsonNode batches = root.path("batches");
        for (JsonNode batch : batches) {
            String serviceName = findStringAttribute(batch.path("resource").path("attributes"), "service.name");
            for (JsonNode scopeSpan : batch.path("scopeSpans")) {
                for (JsonNode span : scopeSpan.path("spans")) {
                    String spanId = decodeBase64ToHex(span.path("spanId").asText(null));
                    String parentSpanId = span.hasNonNull("parentSpanId") ? decodeBase64ToHex(span.path("parentSpanId").asText(null)) : null;
                    long startNanos = Long.parseLong(span.path("startTimeUnixNano").asText("0"));
                    long endNanos = Long.parseLong(span.path("endTimeUnixNano").asText("0"));
                    String kind = span.path("kind").asText("SPAN_KIND_UNSPECIFIED");
                    String statusCode = span.path("status").path("code").asText("STATUS_CODE_UNSET");
                    outSpans.add(new TraceWaterfallView.SpanView(spanId, parentSpanId, span.path("name").asText(""), serviceName, tenant, kind, statusCode, 0, 0));
                    outTimes.add(new long[]{startNanos, endNanos});
                }
            }
        }
    }

    private String findStringAttribute(JsonNode attributes, String key) {
        for (JsonNode attribute : attributes) {
            if (key.equals(attribute.path("key").asText())) {
                return attribute.path("value").path("stringValue").asText(null);
            }
        }
        return null;
    }

    private String decodeBase64ToHex(String base64) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        return HexFormat.of().formatHex(Base64.getDecoder().decode(base64));
    }
}
