package com.opsmind.identity.application.dto;

import java.util.List;

/**
 * SPEC-SC-014: the normalized shape support-console's `TraceWaterfall`
 * renders directly — already relative-offset milliseconds, already
 * hex-decoded IDs, so the browser never has to deal with Tempo's own
 * proto3-JSON base64/int64-as-string encoding (see {@code TempoQueryClient}'s
 * own javadoc for why that parsing happens here instead). Lives in {@code
 * application.dto}, not {@code api.browser}, matching this service's own
 * established convention ({@code BrowserSessionTokenView}) — the
 * application layer must not depend on {@code api} (13-package-and-class-
 * design §Dependency Direction), so its own output DTO cannot live there.
 */
public record TraceWaterfallView(
    String traceId,
    List<SpanView> spans,
    /** Every tenant this trace was actually found under (ADR-0011: a cross-domain trace may legitimately span more than one). */
    List<String> foundInTenants,
    /** Non-empty only on a real, partial Tempo outage — some tenant(s) could not be checked at all, distinct from a clean 404 for that tenant (SPEC-SC-007/019's own outage-vs-absence discipline, applied here). */
    List<String> unavailableTenants
) {

    public record SpanView(
        String spanId,
        String parentSpanId,
        String name,
        String serviceName,
        String tenant,
        String kind,
        String statusCode,
        long startOffsetMs,
        long durationMs
    ) {
    }
}
