package com.opsmind.identity.application.exception;

/** SPEC-SC-014: the trace-waterfall proxy is a support-console-only feature — any other browser-session registration (e.g. domain 09's "opsmind") is denied. */
public class TraceAccessDeniedException extends RuntimeException {

    public TraceAccessDeniedException(String registrationId) {
        super("client registration " + registrationId + " is not authorized to query traces");
    }
}
