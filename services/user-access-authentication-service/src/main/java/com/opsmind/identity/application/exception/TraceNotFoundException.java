package com.opsmind.identity.application.exception;

/** SPEC-SC-014 §16: the trace genuinely was not found under any queried tenant (e.g. outside Tempo's retention window) — a real, clean 404, distinct from {@link TraceQueryUnavailableException}. */
public class TraceNotFoundException extends RuntimeException {

    public TraceNotFoundException(String traceId) {
        super("trace " + traceId + " was not found under any queried tenant");
    }
}
