package com.opsmind.attachment.application.exception;

/** SPEC-EP-011: the real, server-side half of this check — never the security boundary client-side (its own §14). */
public class UnsupportedMimeTypeException extends RuntimeException {

    public UnsupportedMimeTypeException(String mimeType) {
        super("unsupported file type: " + (mimeType == null || mimeType.isBlank() ? "unknown" : mimeType));
    }
}
