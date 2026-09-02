package com.opsmind.identity.application.exception;

/** SPEC-SC-014: every queried tenant failed to respond at all (Tempo/network unreachable) — a real 503, retryable, distinct from a genuine {@link TraceNotFoundException}. */
public class TraceQueryUnavailableException extends RuntimeException {

    public TraceQueryUnavailableException(String traceId) {
        super("no tenant could be queried for trace " + traceId + " — the trace store may be unavailable");
    }
}
