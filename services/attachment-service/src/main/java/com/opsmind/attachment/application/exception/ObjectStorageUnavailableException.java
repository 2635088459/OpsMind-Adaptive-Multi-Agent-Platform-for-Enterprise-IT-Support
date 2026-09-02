package com.opsmind.attachment.application.exception;

/** A real MinIO/S3 call failed (network, bucket missing, credentials) — a dependency-unavailable condition, not a caller error. */
public class ObjectStorageUnavailableException extends RuntimeException {

    public ObjectStorageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
