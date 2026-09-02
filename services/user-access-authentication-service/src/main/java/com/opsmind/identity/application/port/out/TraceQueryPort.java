package com.opsmind.identity.application.port.out;

/** SPEC-SC-014: the trace-store query boundary — {@code TempoQueryClient} (infrastructure) is the real adapter; the application layer only ever depends on this port (13-package-and-class-design §Dependency Direction). */
public interface TraceQueryPort {

    TempoQueryResult queryTrace(String baseUrl, String traceId, String tenant);
}
